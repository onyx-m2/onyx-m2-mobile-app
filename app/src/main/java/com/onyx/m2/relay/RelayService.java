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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.Arrays;
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

    private static final int WEBSOCKET_NORMAL_CLOSURE_STATUS = 1000;
    private static final int WEBSOCKET_ERROR_CLOSURE_STATUS = 2000;

    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gattServer;
    private BluetoothGattCharacteristic configCharacteristic;
    private BluetoothGattCharacteristic relayCharacteristic;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic messageCharacteristic;
    private boolean bleConnected = false;

    private Queue<byte[]> commandQueue;
    private Queue<String> configQueue;

    private OkHttpClient webClient;
    private WebSocket webSocket;
    private boolean webSocketOpen = false;
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

    @Override
    public void onCreate() {
        Log.d(TAG, "Create, thread id: " + Thread.currentThread().getId());
        final BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bleScanner = manager.getAdapter().getBluetoothLeScanner();

        commandQueue = new LinkedList<>();
        configQueue = new LinkedList<>();

        webClient = new OkHttpClient.Builder()
            .pingInterval(2, TimeUnit.SECONDS)
            .build();

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Onyx M2 Channel",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

        Toast.makeText(this, "Onyx Relay Started", Toast.LENGTH_LONG).show();
    }

    public void onDestroy() {
        Log.d(TAG, "Destroy, thread id: " + Thread.currentThread().getId());
        Toast.makeText(this, "Onyx Relay Stopped", Toast.LENGTH_LONG).show();
        if (webSocket != null) {
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Server disconnected");
            setWebSocketState(false, false);
        }
        if (gattServer != null) {
            gattServer.close();
            setBleState(false, false);
        }
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
        messenger = (Messenger) intent.getExtras().get("MESSENGER");
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG, "Rebind");
        messenger = (Messenger) intent.getExtras().get("MESSENGER");
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbind");
        messenger = null;
        return true;
    }

    class M2ScanCallback extends ScanCallback {

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
            connectDevice(device);
        }
    }

    private void scan() {
        Log.d(TAG, "Scanning for M2");

        List<ScanFilter> deviceFilters = Arrays.asList(new ScanFilter.Builder()
                .setDeviceName("Onyx M2")
                .build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();

        bleScanner.startScan(deviceFilters, settings, new M2ScanCallback());
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
                if (webSocket != null) {
                    webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "M2 disconnected");
                }
                setWebSocketState(false, false);
                setBleState(false, true);
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

                        setBleState(true, true);
                        enableCharacteristicNotification(messageCharacteristic);
                        connectWebService();
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
            if (!webSocketOpen) {
                Log.w(TAG, "Ignoring incoming message because websocket is down");
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

    private void connectDevice(BluetoothDevice device) {
        gattServer = device.connectGatt(this, true, new M2GattCallback());
    }

    WebSocketListener webSocketListener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.i(TAG, "Web socket is open");
            setWebSocketState(true, true);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.i(TAG, "m2 <- " + text + " (unsupported)");
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            Log.i(TAG, String.format("m2 <- (%d) %s", bytes.size(), bytes.hex()));
            byte[] command = bytes.toByteArray();
            commandCharacteristic.setValue(command);
            if (!gattServer.writeCharacteristic(commandCharacteristic)) {
                Log.d(TAG, "Queueing command");
                commandQueue.add(command);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            Log.i(TAG, "Web socket is closing: " + code + " / " + reason);
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Server disconnected");
            setWebSocketState(false, true);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.i(TAG, "Web socket error: " + t.getMessage());
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, t.getMessage());
            setWebSocketState(false, true);
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    connectWebService();
                }
            }, 1000);
        }
    };

    private void connectWebService() {
        if (!webSocketOpen) {
            Log.d(TAG, String.format("Connect web service, hostname: %s, pin: %s", webSocketHostname, webSocketPin));
            if (!webSocketHostname.isEmpty() && !webSocketPin.isEmpty()) {
                String url = String.format("wss://%s/relay?pin=%s", webSocketHostname, webSocketPin);
                Request request = new Request.Builder().url(url).build();
                webSocket = webClient.newWebSocket(request, webSocketListener);
            }
        }
    }

    private void setWebSocketState(boolean state, boolean notify) {
        webSocketOpen = state;
        if (notify && gattServer != null) {
            relayCharacteristic.setValue(state ? 1 : 0, FORMAT_UINT8, 0);
            gattServer.writeCharacteristic(relayCharacteristic);
        }
        if (notify) {
            updateServiceNotification();
        }
        if (messenger != null) {
            try {
                messenger.send(Message.obtain(null, state ? MSG_WS_CONNECTED : MSG_WS_DISCONNECTED));
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void setBleState(boolean state, boolean notify) {
        bleConnected = state;
        if (notify) {
            updateServiceNotification();
        }
        if (messenger != null) {
            try {
                messenger.send(Message.obtain(null, state ? MSG_BLE_CONNECTED : MSG_BLE_DISCONNECTED));
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

    public boolean syncConfig() {
        Log.d(TAG, "Sync config, thread id: " + Thread.currentThread().getId());
        if (gattServer == null) {
            Toast.makeText(this, "Onyx M2 Not Connected", Toast.LENGTH_LONG).show();
            return false;
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
        return true;
    }

    Notification createServiceNotification() {
        String title;
        String text;
        if (!bleConnected) {
            title = "Scanning";
            text = "M2 is offline or out of range";
        } else if (!webSocketOpen) {
            title = "Connecting";
            text = "M2 is online, connecting to cloud server";
        } else {
            title = "Online";
            text = "M2 cloud link is active";
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
        manager.notify(SERVICE_NOTIFICATION_ID, createServiceNotification());
    }
}