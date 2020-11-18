package com.onyx.m2.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT8;

public class RelayService extends Service {
    private static final String TAG = "RelayService";
    private static final String SERVICE_CHANNEL_ID = "onyx_m2_service_channel";
    private static final int SERVICE_NOTIFICATION_ID = 1;
    private static final String INSTRUMENT_CLUSTER_CHANNEL_ID = "onyx_m2_instrument_cluster_channel";
    public static final int INSTRUMENT_CLUSTER_NOTIFICATION_ID = 2;

    public static final UUID CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID;

    public static final UUID M2_SERVICE_UUID;
    public static final UUID M2_CONFIG_CHARACTERISTIC_UUID;
    public static final UUID M2_RELAY_CHARACTERISTIC_UUID;
    public static final UUID M2_COMMAND_CHARACTERISTIC_UUID;
    public static final UUID M2_MESSAGE_CHARACTERISTIC_UUID;

    static {
        CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
        M2_SERVICE_UUID = UUID.fromString("e9377e45-d4d2-4fdc-9e1c-448d8b4e05d5");
        M2_CONFIG_CHARACTERISTIC_UUID = UUID.fromString("3c1a503d-06bd-4153-874c-c03e4866f19b");
        M2_RELAY_CHARACTERISTIC_UUID = UUID.fromString("8e9e4115-30a8-4ce6-9362-5afec3315d7d");
        M2_COMMAND_CHARACTERISTIC_UUID = UUID.fromString("25b9cc8b-9741-4beb-81fc-a0df9b155f8d");
        M2_MESSAGE_CHARACTERISTIC_UUID = UUID.fromString("7d363f56-9154-4168-8ee8-034a216edfb4");
    }

    private static final int WEBSOCKET_NORMAL_CLOSURE_STATUS;
    static {
        WEBSOCKET_NORMAL_CLOSURE_STATUS = 1000;
    }

    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gattServer;
    private BluetoothGattCharacteristic configCharacteristic;
    private BluetoothGattCharacteristic relayCharacteristic;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic messageCharacteristic;

    private Queue<byte[]> commandQueue;
    private Queue<String> configQueue;

    private static final int WS_STATE_OPEN = 1;
    private static final int WS_STATE_CLOSED = 2;

    private OkHttpClient webClient;
    private WebSocket webSocket;
    private int webSocketState = WS_STATE_CLOSED;
    private int webSocketDesiredState = WS_STATE_CLOSED;
    private String webSocketHostname;
    private String webSocketPin;
    private int webSocketMsgRate;
    private int webSocketMsgCount;
    private Handler webSocketMsgCountHandler;
    boolean webSocketMessagesDisabled;

    private IBinder binder = new RelayBinder();
    public class RelayBinder extends Binder {
        public RelayService getService() {
            return RelayService.this;
        }
    };

    private MutableLiveData<Boolean> bleConnected;
    public MutableLiveData<Boolean> getBleConnected() {
        return bleConnected;
    }

    private MutableLiveData<Boolean> webSocketConnected;
    public MutableLiveData<Boolean> getWebSocketConnected() {
        return webSocketConnected;
    }

    private MutableLiveData<Boolean> inHolder;
    public MutableLiveData<Boolean> getInHolder() {
        return inHolder;
    }

    // We need to listen for wifi coming up because by default, the os will happily continue to
    // service the web socket using the LTE interface even wifi becomes available. So, we'll
    // cycle the ws when we detect wifi coming up.
    private BroadcastReceiver wifiBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Wifi broadcast receive");
            String action = intent.getAction();
            if (action != null && action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                Log.d(TAG, "Wifi state changed");
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                    Log.d(TAG, "Wifi state enabled");
                    if (webSocketState == WS_STATE_OPEN) {
                        Log.i(TAG, "Scheduling cycling web socket connection in 5s");
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Switching to wifi");
                            }
                        }, 5000);
                    }
                }
            }
        }
    };

    // Listen for battery notifications to detect when the phone starts wireless charging.
    // This, combined with checking that the phone is in landscape mode, automates starting the
    // instrument cluster when the phone is placed into the car mount holder (in all my other
    // wireless chargers, the phone sits upright).
    private boolean showInstrumentClusterOnNextBatteryChange = false;
    private BroadcastReceiver batteryBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Battery broadcast receive");
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(Intent.ACTION_POWER_CONNECTED)) {
                    Log.d(TAG, "Power connected");
                    // this is necessary because BATTERY_PLUGGED_WIRELESS extra is only sent
                    // the ACTION_BATTERY_CHANGED
                    showInstrumentClusterOnNextBatteryChange = true;
                }
                else if (action.equals(Intent.ACTION_POWER_DISCONNECTED)) {
                    Log.d(TAG, "Power disconnected");
                    inHolder.postValue(false);
                }
                else if (action.equals(Intent.ACTION_BATTERY_CHANGED) && showInstrumentClusterOnNextBatteryChange) {
                    showInstrumentClusterOnNextBatteryChange = false;
                    int pluggedState = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                    if (pluggedState == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                        Log.d(TAG, "Plugged into wireless");
                        inHolder.postValue(true);
                        /*Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
                        int rotation = display.getRotation();
                        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                            showInstrumentCluster();
                        }*/
                    }
                }
            }
        }
    };

    // NOTE: If the above turns out to be too aggressive and leads to false positives, we could
    // also try using the motion sensors to detect a vertical slide prior to charging starting.

    @Override
    public void onCreate() {
        Log.d(TAG, "Create, thread id: " + Thread.currentThread().getId());
        final BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) {
            bleScanner = btManager.getAdapter().getBluetoothLeScanner();
        }

        bleConnected = new MutableLiveData<>(false);
        webSocketConnected = new MutableLiveData<>(false);
        inHolder = new MutableLiveData<>();
        inHolder.observeForever(value -> {
            Log.d(TAG, "InHolder onChanged");
            if (value) {
                showInstrumentCluster();
            } else {
                hideInstrumentCluster();
            }
        });

        commandQueue = new LinkedList<>();
        configQueue = new LinkedList<>();

        webClient = new OkHttpClient.Builder()
            .pingInterval(2, TimeUnit.SECONDS)
            .build();

        createNotificationChannels();

        IntentFilter wifiIntentFilter = new IntentFilter();
        wifiIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiBroadcastReceiver, wifiIntentFilter);

        webSocketMsgCountHandler = new Handler();
        webSocketMsgCountHandler.postDelayed(new Runnable(){
            public void run(){
                int prevWebSocketMsgRate = webSocketMsgRate;
                webSocketMsgRate = webSocketMsgCount;
                webSocketMsgCount = 0;
                if (webSocketMsgRate != prevWebSocketMsgRate) {
                    updateServiceNotification();
                }
                webSocketMsgCountHandler.postDelayed(this, 1000);
            }
        }, 1000);

        IntentFilter batteryIntentFilter = new IntentFilter();
        batteryIntentFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        batteryIntentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        batteryIntentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryBroadcastReceiver, batteryIntentFilter);

        EventBus.getDefault().register(this);

        Toast.makeText(this, "Onyx Relay Started", Toast.LENGTH_LONG).show();
    }

    public void onDestroy() {
        Log.d(TAG, "Destroy, thread id: " + Thread.currentThread().getId());
        Toast.makeText(this, "Onyx Relay Stopped", Toast.LENGTH_LONG).show();

        webSocketDesiredState = WS_STATE_CLOSED;
        if (webSocket != null) {
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Service destroyed");
            setWebSocketState(WS_STATE_CLOSED, false);
        }

        if (gattServer != null) {
            gattServer.close();
            setBleConnected(false, false);
        }

        unregisterReceiver(wifiBroadcastReceiver);
        unregisterReceiver(batteryBroadcastReceiver);

        EventBus.getDefault().unregister(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Start command, thread id: " + Thread.currentThread().getId());

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        webSocketHostname = settings.getString("server_hostname", "");
        webSocketPin = settings.getString("server_pin", "");
        Log.d(TAG, String.format("Web service configuration, hostname: %s, pin: %s", webSocketHostname, webSocketPin));

        startForeground(SERVICE_NOTIFICATION_ID, createServiceNotification());
        scan();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Bind");
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG, "Rebind");
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbind");
        return true;
    }

    @Subscribe
    public void onM2Command(M2Command command) {
        commandCharacteristic.setValue(command.data);
        if (!gattServer.writeCharacteristic(commandCharacteristic)) {
            Log.d(TAG, "Queueing command because writing failed");
            commandQueue.add(command.data);
        }
    }

    ScanCallback scanCallback = new ScanCallback() {

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Log.d(TAG, String.format("Scan result batch of size: %d", results.size()));
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.d(TAG, String.format("Scan failed with error code: %d", errorCode));
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.d(TAG, "Scan result, callback type: " + callbackType + ", thread id: " + Thread.currentThread().getId());
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, String.format("From device: %s, address: %s", device.getName(), device.getAddress()));
            bleScanner.stopScan(this);
            gattServer = device.connectGatt(RelayService.this, true, new M2GattCallback());
        }
    };

    private void scan() {
        Log.d(TAG, "Scanning for M2");

        List<ScanFilter> deviceFilters = Collections.singletonList(new ScanFilter.Builder()
                .setDeviceName("Onyx M2")
                .build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();

        bleScanner.startScan(deviceFilters, settings, scanCallback);
    }

    class M2GattCallback extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to M2 GATT server");
                Log.i(TAG, "Attempting to start service discovery");
                gattServer.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);
                gattServer.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from M2 GATT server");
                webSocketDesiredState = WS_STATE_CLOSED;
                if (webSocket != null) {
                    webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "M2 disconnected");
                }
                setWebSocketState(WS_STATE_CLOSED, false);
                setBleConnected(false, true);
            }
        }

        @Override
        // New services discovered
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, String.format("Services discovered , status: %d", status));
            if (status == BluetoothGatt.GATT_SUCCESS) {
                List<BluetoothGattService> services = gatt.getServices();
                Log.d(TAG, String.format("There are %d services", services.size()));
                for (BluetoothGattService service : services) {
                    Log.d(TAG, String.format("  Service %s, instance: %d, type: %d",
                        service.getUuid().toString(),
                        service.getInstanceId(),
                        service.getType()));

                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        Log.d(TAG, String.format("    Characteristic %s, instance: %d, properties: %x",
                            characteristic.getUuid().toString(),
                            characteristic.getInstanceId(),
                            characteristic.getProperties()));
                    }

                    if (service.getUuid().equals(M2_SERVICE_UUID)) {
                        Log.d(TAG, "Found Onyx M2 service, thread id: " + Thread.currentThread().getId());

                        configCharacteristic = service.getCharacteristic(M2_CONFIG_CHARACTERISTIC_UUID);
                        relayCharacteristic = service.getCharacteristic(M2_RELAY_CHARACTERISTIC_UUID);
                        commandCharacteristic = service.getCharacteristic(M2_COMMAND_CHARACTERISTIC_UUID);
                        messageCharacteristic = service.getCharacteristic(M2_MESSAGE_CHARACTERISTIC_UUID);

                        setBleConnected(true, true);
                        enableCharacteristicNotification(messageCharacteristic);
                        webSocketDesiredState = WS_STATE_OPEN;
                        connectWebSocket();
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Characteristic read, status" + status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "Characteristic " + characteristic.getUuid() + " changed");
            if (!characteristic.equals(messageCharacteristic)) {
                Log.w(TAG, "Ignoring non-message characteristic change");
                return;
            }
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) {
                Log.w(TAG, "Ignoring empty characteristic value");
                return;
            }
            M2Message message = new M2Message(data);
            Log.i(TAG, String.format("m2 -> ts: %d, bus: %d, id: %d", message.ts, message.bus, message.id));
            EventBus.getDefault().post(message);
            if (!webSocketMessagesDisabled) {
                if (webSocketState == WS_STATE_CLOSED) {
                    Log.w(TAG, "Incoming message not sent to web socket that is down");
                    return;
                }
                webSocket.send(ByteString.of(data));
                webSocketMsgCount++;
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Characteristic " + characteristic.getUuid() + " written with status " + status);
            if (!configQueue.isEmpty()) {
                Log.d(TAG, "Writing next queued config");
                configCharacteristic.setValue(configQueue.remove());
                gattServer.writeCharacteristic(configCharacteristic);
            }
            else if (!commandQueue.isEmpty()) {
                Log.d(TAG, "Writing next queued command");
                commandCharacteristic.setValue(commandQueue.remove());
                gattServer.writeCharacteristic(commandCharacteristic);
            }
        }
    }

    WebSocketListener webSocketListener = new WebSocketListener() {
        @Override
        public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
            Log.i(TAG, "Web socket is open");
            setWebSocketState(WS_STATE_OPEN, true);
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
            Log.i(TAG, "m2 <- " + text + " (unsupported)");
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, ByteString bytes) {
            Log.i(TAG, String.format("m2 <- (%d) %s", bytes.size(), bytes.hex()));
            byte[] data = bytes.toByteArray();

            // temporary patch: if the server is disabling all messages (because all local
            // its clients have stopped, set a flag to save bandwidth but don't actually
            // forward to M2 because direct interface still needs these; only way to do this
            // properly is to manage clientIds in the firmware
            M2Command command = new M2Command(data);
            if (command.isDisableAllMessages()) {
                webSocketMessagesDisabled = true;
            }
            else {
                webSocketMessagesDisabled = false;
                EventBus.getDefault().post(command);
            }

        }

        // Remote is closing the connection
        @Override
        public void onClosing(@NotNull WebSocket ws, int code, @NotNull String reason) {
            Log.i(TAG, "Web socket is closing: " + code + " / " + reason);
            if (ws == webSocket) {
                webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Server disconnected");
                setWebSocketState(WS_STATE_CLOSED, true);
            }
        }

        // Error or timeout on the connection
        @Override
        public void onFailure(@NotNull WebSocket ws, Throwable t, Response response) {
            Log.i(TAG, "Web socket error: " + t.getMessage());
            if (ws == webSocket) {
                webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, t.getMessage());
                webSocket = null;
                setWebSocketState(WS_STATE_CLOSED, true);
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        connectWebSocket();
                    }
                }, 1000);
            }
        }

        @Override
        public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
            Log.d(TAG, "Web socket closed: " + code + " / " + reason);
            if (ws == webSocket) {
                webSocket = null;
                if (webSocketDesiredState == WS_STATE_OPEN) {
                    Log.i(TAG, "Reconnecting web socket on close");
                    connectWebSocket();
                }
            }
        }
    };

    private void connectWebSocket() {
        Log.d(TAG, "Connect web socket");
        if (webSocketState == WS_STATE_CLOSED && webSocketDesiredState == WS_STATE_OPEN) {
            Log.d(TAG, String.format("Web socket config, hostname: %s, pin: %s", webSocketHostname, webSocketPin));
            if (!webSocketHostname.isEmpty() && !webSocketPin.isEmpty()) {
                String url = String.format("wss://%s/relay?pin=%s", webSocketHostname, webSocketPin);
                Request request = new Request.Builder().url(url).build();
                webSocket = webClient.newWebSocket(request, webSocketListener);
            }
        }
    }

    private void setWebSocketState(int state, boolean notify) {
        webSocketState = state;
        webSocketConnected.postValue(state == WS_STATE_OPEN);
        if (notify && gattServer != null) {
            relayCharacteristic.setValue(state == WS_STATE_OPEN ? 1 : 0, FORMAT_UINT8, 0);
            gattServer.writeCharacteristic(relayCharacteristic);
        }
        if (notify) {
            updateServiceNotification();
        }
    }

    private void setBleConnected(boolean connected, boolean notify) {
        bleConnected.postValue(connected);
        if (notify) {
            updateServiceNotification();
        }
    }

    private void enableCharacteristicNotification(BluetoothGattCharacteristic characteristic) {
        gattServer.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gattServer.writeDescriptor(descriptor);
    }

    private void setConfig(String config) {
        configCharacteristic.setValue(config);
        if (!gattServer.writeCharacteristic(configCharacteristic)) {
            Log.d(TAG, "Queueing config write: " + config);
            configQueue.add(config);
        }
    }

    public void syncConfig() {
        Log.d(TAG, "Sync config, thread id: " + Thread.currentThread().getId());
        if (gattServer == null) {
            Toast.makeText(this, "Onyx M2 Not Connected", Toast.LENGTH_LONG).show();
            return;
        }
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        webSocketHostname = settings.getString("server_hostname", "");
        webSocketPin = settings.getString("server_pin", "");
        setConfig("SH=" + webSocketHostname);
        setConfig("SP=" + webSocketPin);
        int homeWifiEnabled = settings.getBoolean("home_wifi_enabled", false) ? 1 : 0;
        setConfig("HE=" + homeWifiEnabled);
        setConfig("HS=" + settings.getString("home_wifi_ssid", ""));
        setConfig("HP=" + settings.getString("home_wifi_password", ""));
        int mobileWifiEnabled = settings.getBoolean("mobile_wifi_enabled", false) ? 1 : 0;
        setConfig("ME=" + mobileWifiEnabled);
        setConfig("MS=" + settings.getString("mobile_wifi_ssid", ""));
        setConfig("MP=" + settings.getString("mobile_wifi_password", ""));
        setConfig("RESET");
        Toast.makeText(this, "Updating Onyx M2 Config", Toast.LENGTH_LONG).show();
    }

    void createNotificationChannels() {
        NotificationManager manager = ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
        if (manager == null) {
            Log.e(TAG, "Notification manager not available in createNotificationChannels()");
            return;
        }

        NotificationChannel relayChannel = new NotificationChannel(SERVICE_CHANNEL_ID,
                "Relay",
                NotificationManager.IMPORTANCE_LOW);
        relayChannel.setShowBadge(false);
        manager.createNotificationChannel(relayChannel);

        NotificationChannel instrumentClusterChannel = new NotificationChannel(INSTRUMENT_CLUSTER_CHANNEL_ID,
                "Instrument Cluster",
                NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(instrumentClusterChannel);
    }

    Notification createServiceNotification() {
        String title;
        String text;
        int colour = 0xFFFFFFFF;;
        if (webSocketMsgRate > 0) {
            title = "Active";
            text = "Relaying " + webSocketMsgRate + " msgs/sec";
            colour = 0xFFC90000;
        } else if (!bleConnected.getValue()) {
            title = "Idle";
            text = "Car is offline or out of range";
        } else if (webSocketState == WS_STATE_CLOSED) {
            title = "Connected";
            text = "Car is connected";
        } else {
            title = "Online";
            text = "Car is online";
        }
        return new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_swap_horiz_black_24dp)
            .setColor(colour)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0))
            .build();
    }

    void updateServiceNotification() {
        NotificationManager manager = ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
        if (manager != null) {
            manager.notify(SERVICE_NOTIFICATION_ID, createServiceNotification());
        }
    }

    void showInstrumentCluster() {
        NotificationManager manager = ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
        if (manager == null) {
            Log.e(TAG, "Notification manager not available");
            return;
        }

        Intent fullScreenIntent = new Intent(this, InstrumentClusterActivity.class);
        fullScreenIntent.setAction("onyx.intent.action.IN_HOLDER");
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(this, 0,
                fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification  = new NotificationCompat.Builder(this, INSTRUMENT_CLUSTER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_swap_horiz_black_24dp)
            .setContentTitle("Onyx M2")
            .setContentTitle("Instrument Cluster")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build();

        manager.notify(INSTRUMENT_CLUSTER_NOTIFICATION_ID, notification);
    }

    void hideInstrumentCluster() {
        // dismiss the notification that probably started the activity, as the fullscreen
        // intent is being used a vehicle to launch the activity from the background, it's not
        // a real notification, and should never be visible as such
        NotificationManager manager = ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
        if (manager != null) {
            manager.cancel(INSTRUMENT_CLUSTER_NOTIFICATION_ID);
        }
    }
}