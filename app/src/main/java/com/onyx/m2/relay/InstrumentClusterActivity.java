package com.onyx.m2.relay;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Arrays;

/**
 * The instrument cluster used to display the realtime gauges in a web view. It hides the system
 * UI (i.e. status bar and navigation/system bar), and always stays on. It also injects the M2
 * interface into the scripting environment for the web app to send commands and receive messages
 * directly without having to use the server.
 */
public class InstrumentClusterActivity extends AppCompatActivity {
    private static final String TAG = "InstrumentClusterActivity";

    private WebView webView;
    private RelayService relayService;
    private ServiceConnection relayConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, "Service connected");
            relayService = ((RelayService.RelayBinder) binder).getService();
            relayService.getInHolder().observe(InstrumentClusterActivity.this, value -> {
                Log.d(TAG, "InHolder Changed: " + value);
                if (!value) {
                    String action = getIntent().getAction();
                    if (action != null && action.equals("onyx.intent.action.IN_HOLDER")) {
                        finishAndRemoveTask();
                    }
                }
            });
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Service disconnected");
            relayService = null;
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "Create");
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
        webView = (WebView)findViewById(R.id.fullscreen_content);
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

        // inject our M2 interface into the JS namespace
        webView.addJavascriptInterface(new M2WebAppInterface(this), "M2");

        // launch the web app, using black as the background colour to avoid a white flash
        // during load
        webView.setBackgroundColor(Color.BLACK);
        webView.loadUrl("https://eic.onyx-m2-dashboard.net/");
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "Start");
        super.onStart();
        Intent intent = new Intent(this, RelayService.class);
        bindService(intent, relayConnection, Context.BIND_AUTO_CREATE);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onUserInteraction() {
        webView.reload();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "Stop");
        super.onStop();
        unbindService(relayConnection);
        finishAndRemoveTask();
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "Destroy");

        // no idea why the webview needs to be explicitly destroyed, but it does; failure
        // to do this will result in the M2 continuing to send events forever
        webView.destroy();

        super.onDestroy();
    }

    /**
     * An M2 message is emitted into the event bus. The creative way this is sent to the
     * Javascript side is needed as any function that looks like it's not being called in
     * the Javascript world will get minified away by Webpack. By using the window's event
     * system, this is prevented. The Javascript to receive these events looks like this:
     *
     *   window.addEventListener('m2', ({ detail: [ ts, bus, id, data ] }) => {
     *     console.log(`ts: ${ts}, bus: ${bus}, id: ${id}, data: ${data}`)
     *   })
     */
    @SuppressLint("DefaultLocale")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onM2Message(M2Message msg) {
        Log.d(TAG, "M2 Message ts: " + msg.ts + ", bus: " + msg.bus + ", id: " + msg.id);
        webView.evaluateJavascript(String.format("window.dispatchEvent(new CustomEvent('m2', { detail: [%d, %d, %d, %s] }))",
                msg.ts, msg.bus, msg.id, Arrays.toString(msg.data)), null);
    }
}