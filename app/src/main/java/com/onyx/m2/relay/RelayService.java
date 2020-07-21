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
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

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
    private static final String CHANNEL_ID = "onyx_m2_service_channel";
    private static final int SERVICE_NOTIFICATION_ID = 1;

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
    private boolean bleConnected = false;

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

    private Messenger messenger;
    private IBinder binder = new RelayBinder();
    public class RelayBinder extends Binder {
        RelayService getService() {
            return RelayService.this;
        }
    };

    public static final int MSG_BLE_CONNECTED = 1;
    public static final int MSG_BLE_DISCONNECTED = 2;
    public static final int MSG_WS_CONNECTED = 3;
    public static final int MSG_WS_DISCONNECTED = 4;

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

    @Override
    public void onCreate() {
        Log.d(TAG, "Create, thread id: " + Thread.currentThread().getId());
        final BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) {
            bleScanner = btManager.getAdapter().getBluetoothLeScanner();
        }

        commandQueue = new LinkedList<>();
        configQueue = new LinkedList<>();

        webClient = new OkHttpClient.Builder()
            .pingInterval(2, TimeUnit.SECONDS)
            .build();

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Onyx M2 Channel",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager notifManager = ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
        if (notifManager != null) {
            notifManager.createNotificationChannel(channel);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiBroadcastReceiver, filter);

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
        Bundle extras = intent.getExtras();
        if (extras != null) {
            messenger = (Messenger) extras.get("MESSENGER");
        }
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG, "Rebind");
        Bundle extras = intent.getExtras();
        if (extras != null) {
            messenger = (Messenger) extras.get("MESSENGER");
        }
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbind");
        messenger = null;
        return true;
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
            if (webSocketState == WS_STATE_CLOSED) {
                Log.w(TAG, "Ignoring incoming message because web socket is down");
                return;
            }
            ByteString bytes = ByteString.of(data);
            Log.i(TAG, String.format("m2 <- (%d) %s", bytes.size(), bytes.hex()));
            webSocket.send(bytes);
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
        public void onOpen(WebSocket ws, Response response) {
            Log.i(TAG, "Web socket is open");
            setWebSocketState(WS_STATE_OPEN, true);
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            Log.i(TAG, "m2 <- " + text + " (unsupported)");
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            Log.i(TAG, String.format("m2 <- (%d) %s", bytes.size(), bytes.hex()));
            byte[] command = bytes.toByteArray();
            commandCharacteristic.setValue(command);
            if (!gattServer.writeCharacteristic(commandCharacteristic)) {
                Log.d(TAG, "Queueing command");
                commandQueue.add(command);
            }
        }

        // Remote is closing the connection
        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            Log.i(TAG, "Web socket is closing: " + code + " / " + reason);
            if (ws == webSocket) {
                webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Server disconnected");
                setWebSocketState(WS_STATE_CLOSED, true);
            }
        }

        // Error or timeout on the connection
        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
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
        public void onClosed(WebSocket ws, int code, String reason) {
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
        if (notify && gattServer != null) {
            relayCharacteristic.setValue(state == WS_STATE_OPEN ? 1 : 0, FORMAT_UINT8, 0);
            gattServer.writeCharacteristic(relayCharacteristic);
        }
        if (notify) {
            updateServiceNotification();
        }
        if (messenger != null) {
            try {
                messenger.send(Message.obtain(null, state == WS_STATE_OPEN ? MSG_WS_CONNECTED : MSG_WS_DISCONNECTED));
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void setBleConnected(boolean connected, boolean notify) {
        bleConnected = connected;
        if (notify) {
            updateServiceNotification();
        }
        if (messenger != null) {
            try {
                messenger.send(Message.obtain(null, connected ? MSG_BLE_CONNECTED : MSG_BLE_DISCONNECTED));
            } catch (RemoteException e) {
                e.printStackTrace();
            }
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

    public boolean isBleConnected() {
        return bleConnected;
    }

    public boolean isWebSocketConnected() {
        return webSocketState == WS_STATE_OPEN;
    }

    Notification createServiceNotification() {
        String title;
        String text;
        if (!bleConnected) {
            title = "Scanning for Onyx M2";
            text = "Device is offline or out of range";
        } else if (webSocketState == WS_STATE_CLOSED) {
            title = "Connecting to Onyx M2 server";
            text = "Device is online, connecting to cloud server";
        } else {
            title = "Onyx M2 Online";
            text = "Cloud link is active";
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_broken_image_red_24dp)
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
}