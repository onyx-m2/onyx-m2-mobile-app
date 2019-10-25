package com.onyx.m2.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
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
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;

public class RelayService extends Service {
    private static final String TAG = "RelayService";
    private static final String CHANNEL_ID = "onyx_relay_service_channel";

    public static final String DFROBOT_SERIAL_UUID = "0000dfb1-0000-1000-8000-00805f9b34fb";
    private static final int WEBSOCKET_NORMAL_CLOSURE_STATUS = 1000;
    private static final int WEBSOCKET_ERROR_CLOSURE_STATUS = 2000;

    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gattServer;
    private BluetoothGattCharacteristic serialCharacteristic;
    private Queue<ByteString> serialWriteQueue;
    private boolean serialWriteInFlight;
    private byte[] inboundSerialBuffer;

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

        serialWriteQueue = new LinkedList<>();

        webClient = new OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
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
                .setDeviceAddress("C4:BE:84:E2:07:C0")
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
                Log.i(TAG, "Connected to GATT server.");
                Log.i(TAG, "Attempting to start service discovery");
                gattServer.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "M2 disconnected");
                webSocketOpen = false;
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

                        // Are we talking to a DFRobot BLE-Link Xbee?
                        String uuid = characteristic.getUuid().toString();
                        int properties = characteristic.getProperties();
                        if (uuid.equals(DFROBOT_SERIAL_UUID) && (properties & PROPERTY_NOTIFY) == PROPERTY_NOTIFY) {
                            Log.d(TAG, "     ^^^ DFRobot Serial");
                            serialCharacteristic = characteristic;
                            connectWebService();
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, String.format("Chanacteristic read, status: %d", status));
        }

        public String byteArrayToHex(byte[] a, int len) {
            StringBuilder sb = new StringBuilder(len * 3);
            for(byte b: a)
                sb.append(String.format("%02x ", b));
            return sb.toString();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            final int CAN_MSG_LENGTH_OFFSET = 6;
            final int CAN_MSG_SIZE = 7;
            final int CAN_MSG_MAX_DATA_SIZE = 8;
            final int CAN_MSG_MAX_SIZE = CAN_MSG_SIZE + CAN_MSG_MAX_DATA_SIZE;

            byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                Log.d(TAG, String.format("m2 -> (%d) %s", data.length, byteArrayToHex(data, data.length)));

                // append the new data to the existing data in the inbound serial buffer
                if (inboundSerialBuffer != null) {
                    byte[] inboundData = new byte[inboundSerialBuffer.length + data.length];
                    System.arraycopy(inboundSerialBuffer, 0, inboundData, 0, inboundSerialBuffer.length);
                    System.arraycopy(data, 0, inboundData, inboundSerialBuffer.length, data.length);
                    inboundSerialBuffer = inboundData;
                }
                else {
                    inboundSerialBuffer = data;
                }

                // loop on incoming data until we've exhausted what's there
                while (true) {

                    // if there isn't even enough data to lookup the message length, bail
                    if (inboundSerialBuffer.length < CAN_MSG_SIZE) {
                        break;
                    }

                    // if the length is out bounds, assume an incorrect frame was sent, and clear
                    // all incoming data then bail
                    int dataLength = inboundSerialBuffer[CAN_MSG_LENGTH_OFFSET];
                    if (dataLength < 0 || dataLength > CAN_MSG_MAX_DATA_SIZE ) {
                        Log.w(TAG, String.format("m2 sent an invalid frame with data length %d", dataLength));
                        inboundSerialBuffer = null;
                        break;
                    }

                    // if there's not enough data for a full message, bail
                    int len = dataLength + CAN_MSG_SIZE;
                    if (len > inboundSerialBuffer.length) {
                        break;
                    }

                    // if the web socket is open, send the message along (will be lost if not
                    // connected
                    if (webSocketOpen) {
                        Log.d(TAG, String.format("ws <- (%d) %s", len, byteArrayToHex(inboundSerialBuffer, len)));
                        webSocket.send(ByteString.of(inboundSerialBuffer, 0, len));
                    }

                    // slice off the data we just sent and if there's more, keep looping
                    int extraData = inboundSerialBuffer.length - len;
                    if (extraData > 0) {
                        inboundSerialBuffer = Arrays.copyOfRange(inboundSerialBuffer, len, inboundSerialBuffer.length);
                    } else {
                        inboundSerialBuffer = null;
                        break;
                    }
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            serialWriteInFlight = false;
            flushSerialWriteQueue();
        }
    }

    public void connectDevice(BluetoothDevice device) {
        gattServer = device.connectGatt(this, true, new M2GattCallback());

    }

    WebSocketListener webSocketListener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.i(TAG, "Web socket is open");
            webSocketOpen = true;
            gattServer.setCharacteristicNotification(serialCharacteristic, true);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.i(TAG, "m2 <- " + text + " (unsupported)");
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            Log.i(TAG, String.format("m2 <- (%d) %s", bytes.size(), bytes.hex()));
            serialWriteQueue.add(bytes);
            flushSerialWriteQueue();
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, "Server disconnected");
            Log.i(TAG, "Web socket is closing: " + code + " / " + reason);
            webSocketOpen = false;
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.i(TAG, "Web socket error: " + t.getMessage());
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE_STATUS, t.getMessage());
            webSocketOpen = false;
            connectWebService();
        }
    };

    public void connectWebService() {
        Log.d(TAG, "Connecting to web service");
        if (!webSocketOpen) {
            Request request = new Request.Builder()
                    .url("wss://onyx.ngrok.io/m2device?pin=1379")
                    .build();
            webSocket = webClient.newWebSocket(request, webSocketListener);
        }
    }

    public void flushSerialWriteQueue() {
        if (!serialWriteInFlight && !serialWriteQueue.isEmpty()) {
            serialWriteInFlight = true;
            serialCharacteristic.setValue(serialWriteQueue.remove().toByteArray());
            gattServer.writeCharacteristic(serialCharacteristic);
        }
    }
}