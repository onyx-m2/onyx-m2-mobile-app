package com.onyx.m2.relay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private MenuItem startStopAction;

    private RelayService relayService;
    private ServiceConnection relayConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, "Service connected");
            relayService = ((RelayService.RelayBinder) binder).getService();
            relayService.getBleConnected().observe(MainActivity.this, value -> {
                setLinkConnected(R.id.bleImage, R.id.bleConnectedImage, value);
            });
            relayService.getWebSocketConnected().observe(MainActivity.this, value -> {
                setLinkConnected(R.id.wsImage, R.id.wsConnectedImage, value);
            });
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "Create");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        findViewById(R.id.sync).setOnClickListener(view ->  {
            Log.d(TAG, "Sync button click");
            if (relayService != null) {
                relayService.syncConfig();
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
        Intent intent = new Intent(this, RelayService.class);
        bindService(intent, relayConnection, Context.BIND_AUTO_CREATE);
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
            case R.id.action_start_stop: {
                Intent intent = new Intent(this, RelayService.class);
                if (relayService != null) {
                    stopService(intent);
                } else {
                    startService(intent);
                }
                return true;
            }

            case R.id.action_instrument_cluster: {
                Intent intent = new Intent(this, InstrumentClusterActivity.class);
                startActivity(intent);
                return true;
            }

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void setLinkConnected(int imageId, int linkId, boolean connected) {
        int visibility = connected ? View.VISIBLE : View.INVISIBLE;
        float alpha = connected ? 1.0f : 0.5f;
        findViewById(linkId).setVisibility(visibility);
        findViewById(imageId).setAlpha(alpha);
    }
}
