package com.onyx.m2.relay;

import androidx.appcompat.app.AppCompatActivity;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.onyx.m2.relay.R;
import com.onyx.m2.relay.RelayService;

/**
 * The instrument cluster used to display the realtime gauges in a web view. It hides the system
 * UI (i.e. status bar and navigation/system bar), and always stays on. It also injects the M2
 * interface into the scripting environment for the web app to send commands and receive messages
 * directly without having to use the server.
 */
public class InstrumentClusterActivity extends AppCompatActivity {
    private static final String TAG = "InstrumentClusterActivity";

    private RelayService relayService;
    private ServiceConnection relayConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, "Service connected");
            relayService = ((RelayService.RelayBinder) binder).getService();
            relayService.getInHolder().observe(InstrumentClusterActivity.this, value -> {
                if (!value) {
                    finishAndRemoveTask();
                }
            });
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Service disconnected");
            relayService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instrument_cluster);

        // in order to show when the phone is "off and/or locked", the screen must be turned on,
        // this activity must flag itself as being allowed to show on the lock screen, and the
        // keyguard must be dismissed
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null) {
            keyguardManager.requestDismissKeyguard(this, null);
        }

        // setup the web view to display fullscreen and "immersive", that is, no other
        // screen clutter like the top status bar or bottom navigation buttons
        WebView webView = (WebView)findViewById(R.id.fullscreen_content);
        webView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // to run in hud mode (projected onto the windshield), the interface would need to
        // be flipped horizontally
        // webView.setScaleY(-1);

        // in order to run the onyx-m2-dashboad app properly, javascript and storage needs to
        // be enabled
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // launch the web app, using black as the background colour to avoid a white flash
        // during load
        webView.setBackgroundColor(Color.BLACK);
        webView.loadUrl("https://johnmccalla.ngrok.io/hud");
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
        finishAndRemoveTask();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "Destroy");
        super.onDestroy();
    }

    private void processMessage(byte[] data) {
        int ts = data[0] | (data[1] << 8) | (data[2] << 16) | (data[3] << 24);
        int bus = data[4];
        int id = data[5] | (data[6] << 8);
        int len = data[7];
        //((TextView)findViewById(R.id.fullscreen_content)).setText("id: " + id);
    }
}