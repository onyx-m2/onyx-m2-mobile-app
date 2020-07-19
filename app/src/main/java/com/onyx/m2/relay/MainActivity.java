package com.onyx.m2.relay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private MenuItem startStopAction;

    private RelayService relayService;
    private ServiceConnection relayConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, "Service connected");
            relayService = ((RelayService.RelayBinder) binder).getService();
            if (startStopAction != null) {
                startStopAction.setTitle("Stop Relay");
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Service disconnected");
            relayService = null;
            if (startStopAction != null) {
                startStopAction.setTitle("Start Relay");
            }
        }
    };

    private Handler relayHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case RelayService.MSG_BLE_CONNECTED:
                    MainActivity.this.findViewById(R.id.bleConnectedImage).setVisibility(View.VISIBLE);
                    MainActivity.this.findViewById(R.id.bleImage).setAlpha(1.0f);
                    break;
                case RelayService.MSG_BLE_DISCONNECTED:
                    MainActivity.this.findViewById(R.id.bleConnectedImage).setVisibility(View.INVISIBLE);
                    MainActivity.this.findViewById(R.id.bleImage).setAlpha(0.5f);
                    break;
                case RelayService.MSG_WS_CONNECTED:
                    MainActivity.this.findViewById(R.id.wsConnectedImage).setVisibility(View.VISIBLE);
                    MainActivity.this.findViewById(R.id.wsImage).setAlpha(1.0f);
                    break;
                case RelayService.MSG_WS_DISCONNECTED:
                    MainActivity.this.findViewById(R.id.wsConnectedImage).setVisibility(View.INVISIBLE);
                    MainActivity.this.findViewById(R.id.wsImage).setAlpha(0.5f);
                    break;
            }
            return true;
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "Create");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        FloatingActionButton fab = findViewById(R.id.sync);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "Sync button click");
                if (relayService != null) {
                    relayService.syncConfig();
                }
            }
        });

        startService(new Intent(this, RelayService.class));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.top_menu, menu);
        startStopAction = menu.findItem(R.id.action_start_stop);
        if (relayService == null) {
            startStopAction.setTitle("Start Relay");
        }
        return true;
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "Start");
        bindRelay();
        super.onStart();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "Stop");
        unbindService(relayConnection);
        super.onStop();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_start_stop:
                Intent intent = new Intent(this, RelayService.class);
                if (relayService != null) {
                    stopService(intent);
                } else {
                    startService(intent);
                }
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void bindRelay() {
        Intent intent = new Intent(this, RelayService.class);
        intent.putExtra("MESSENGER", new Messenger(relayHandler));
        bindService(intent, relayConnection, Context.BIND_AUTO_CREATE);
    }
}
