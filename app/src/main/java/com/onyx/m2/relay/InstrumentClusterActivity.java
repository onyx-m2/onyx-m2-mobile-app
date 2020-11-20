package com.onyx.m2.relay;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The instrument cluster used to display the realtime gauges in a web view. It hides the system
 * UI (i.e. status bar and navigation/system bar), and always stays on. It also injects the M2
 * interface into the scripting environment for the web app to send commands and receive messages
 * directly without having to use the server.
 */
public class InstrumentClusterActivity extends AppCompatActivity {
    private static final String TAG = "InstrumentClusterActivity";

    private boolean connected;
    private SharedPreferences preferences;
    private WebView webView;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> statusUpdaterHandle;

    private RelayService relayService;
    private ServiceConnection relayConnection = new ServiceConnection() {

        @SuppressLint("DefaultLocale")
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
            relayService.getBleConnected().observe(InstrumentClusterActivity.this, value -> {
                connected = value;
                updateM2Status(connected, 0, 0);
            });
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Service disconnected");
            relayService = null;
            connected = false;
            updateM2Status(false, 0, 0);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "Create");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instrument_cluster);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

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
        webView = findViewById(R.id.fullscreen_content);
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
        webView.addJavascriptInterface(this, "M2");

        // use black as the background colour to avoid a white flash
        // during load
        webView.setBackgroundColor(Color.BLACK);

        // determine which app we want to start (dev or prod), enable content debugging if
        // dev, and load the web view
        boolean useDevelopment = preferences.getBoolean("eic_use_development", false);
        String hostname;
        if (useDevelopment) {
            hostname = preferences.getString("eic_development_hostname", "");
            WebView.setWebContentsDebuggingEnabled(true);
        } else {
             hostname = preferences.getString("eic_hostname", "");
        }
        webView.loadUrl("https://" + hostname);
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "Start");
        super.onStart();
        Intent intent = new Intent(this, RelayService.class);
        bindService(intent, relayConnection, Context.BIND_AUTO_CREATE);
        EventBus.getDefault().register(this);
        statusUpdaterHandle = scheduler.scheduleWithFixedDelay(() -> updateM2Status(connected, 0, 0),
            1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void onUserInteraction() {
        webView.reload();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "Stop");
        super.onStop();
        statusUpdaterHandle.cancel(false);
        EventBus.getDefault().unregister(this);
        unbindService(relayConnection);
        finishAndRemoveTask();
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
        sendM2Event("message", String.format("[%d, %d, %d, %s]", msg.ts, msg.bus, msg.id, Arrays.toString(msg.data)));
    }

    /** Get a preference value. This allows the web app to have the same access to the
     *  configuration as the native side does. */
    @JavascriptInterface
    public String getPreference(String name) {
        Log.d(TAG, "getPreference: " + name);
        return preferences.getString(name, "");
    }

    /** Send a command to M2 using direct interface. */
    @JavascriptInterface
    public void sendCommand(String array) {
        Log.d(TAG, "sendCommand: " + array);
        if (!connected) {
            Log.e(TAG, "Attempting to send M2 a command while its not connected: " + array);
            return;
        }
        try {
            JSONArray json = new JSONArray(array);
            byte[] data = new byte[json.length()];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) json.getInt(i);
            }
            EventBus.getDefault().post(new M2Command(data));
        }
        catch (JSONException e) {
            Log.e(TAG, "Invalid command: " + array);
        }
    }

    /**
     * Set the connect state, updating the web app in the process.
     */
    @SuppressLint("DefaultLocale")
    void updateM2Status(boolean connected, int latency, int rate) {
        sendM2Event("status", String.format("[%b, %d, %d]", connected, latency, rate));
    }

    /**
     * Send an M2 event to the web app. The event must be a single word, and the data should be
     * valid Javascript for a primitive value, an object, or an array.
     */
    void sendM2Event(String event, String data) {
        String command = String.format("window.dispatchEvent(new CustomEvent('m2', { detail: { event: '%s', data: %s }}))",
            event, data);
        runOnUiThread(() -> webView.evaluateJavascript(command, null));
    }

}