package com.onyx.m2.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

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
    private static final String CHANNEL_ID = "onyx_relay_service_channel";

    public static final UUID CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID;

    public static final UUID M2_SERVICE_UUID;
    public static final UUID M2_RELAY_CHARACTERISTIC_UUID;
    public static final UUID M2_COMMAND_CHARACTERISTIC_UUID;
    public static final UUID M2_MESSAGE_CHARACTERISTIC_UUID;

    static {
        CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
        M2_SERVICE_UUID = UUID.fromString("e9377e45-d4d2-4fdc-9e1c-448d8b4e05d5");
        M2_RELAY_CHARACTERISTIC_UUID = UUID.fromString("8e9e4115-30a8-4ce6-9362-5afec3315d7d");
        M2_COMMAND_CHARACTERISTIC_UUID = UUID.fromString("25b9cc8b-9741-4beb-81fc-a0df9b155f8d");
        M2_MESSAGE_CHARACTERISTIC_UUID = UUID.fromString("7d363f56-9154-4168-8ee8-034a216edfb4");
    }

    private static final int WEBSOCKET_NORMAL_CLOSURE_STATUS = 1000;
    private static final int WEBSOCKET_ERROR_CLOSURE_STATUS = 2000;

    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gattServer;
    private BluetoothGattCharacteristic relayCharacteristic;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic messageCharacteristic;

    private Queue<byte[]> commandQueue;

    private OkHttpClient webClient;
    private WebSocket webSocket;
    private boolean webSocketOpen = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        final BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bleScanner = manager.getAdapter().getBluetoothLeScanner();

        commandQueue = new LinkedList<>();

        webClient = new OkHttpClient.Builder()
                .pingInterval(4, TimeUnit.SECONDS)
                .build();

        Log.d(TAG, "Created");
    }

    public void onDestroy() {
        Toast.makeText(this, "Onyx Relay Stopped", Toast.LENGTH_LONG).show();
        Log.d(TAG, "Destroyed");
        gattServer.close();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "Onyx Relay Started", Toast.LENGTH_LONG).show();
        Log.d(TAG, "Start command");

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Onyx Relay Channel",
                NotificationManager.IMPORTANCE_LOW);

        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("content title")
                .setContentText("content text")
                .setTicker("ticker text")
                .build();

        startForeground(1, notification);

        scan();
        return START_STICKY;
    }

    public class M2ScanCallback extends ScanCallback {

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
            Log.d(TAG, String.format("Scan result with callback type: %d", callbackType));
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, String.format("From device: %s, address: %s", device.getName(), device.getAddress()));
            bleScanner.stopScan(this);
            connectDevice(device);
        }
    }

    public void scan() {
        Log.d(TAG, "Scanning for M2");

        List<ScanFilter> deviceFilters = Arrays.asList(new ScanFilter.Builder()
                .setDeviceName("Onyx M2")
                .build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();

        bleScanner.startScan(deviceFilters, settings, new M2ScanCallback());
    }

    public class M2GattCallback extends BluetoothGattCallback {

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
                        Log.d(TAG, "Found Onyx M2 service");
                        relayCharacteristic = service.getCharacteristic(M2_RELAY_CHARACTERISTIC_UUID);
                        commandCharacteristic = service.getCharacteristic(M2_COMMAND_CHARACTERISTIC_UUID);
                        messageCharacteristic = service.getCharacteristic(M2_MESSAGE_CHARACTERISTIC_UUID);
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
            if (!commandQueue.isEmpty()) {
                Log.d(TAG, "Writing next queued command");
                commandCharacteristic.setValue(commandQueue.remove());
                gattServer.writeCharacteristic(commandCharacteristic);
            }
        }
    }

    public void connectDevice(BluetoothDevice device) {
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
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Server disconnected");
            Log.i(TAG, "Web socket is closing: " + code + " / " + reason);
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

    public void connectWebService() {
        Log.d(TAG, "Connecting to web service");
        if (!webSocketOpen) {
            Request request = new Request.Builder()
                .url("wss://onyx-m2.net/relay?pin={xxx}")
                .build();
            webSocket = webClient.newWebSocket(request, webSocketListener);
        }
    }

    private void setWebSocketState(boolean state, boolean notify) {
        webSocketOpen = state;
        if (notify) {
            relayCharacteristic.setValue(state ? 1 : 0, FORMAT_UINT8, 0);
            gattServer.writeCharacteristic(relayCharacteristic);
        }
    }

    private void enableCharacteristicNotification(BluetoothGattCharacteristic characteristic) {
        gattServer.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gattServer.writeDescriptor(descriptor);
    }
}