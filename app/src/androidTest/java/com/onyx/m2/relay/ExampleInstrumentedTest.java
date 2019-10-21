package com.onyx.m2.relay;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.rule.ServiceTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    @Test
    public void useBoundService() {

        // Create the service Intent.
        Intent serviceIntent =
                new Intent(InstrumentationRegistry.getTargetContext(), RelayService.class);

        InstrumentationRegistry.getTargetContext().startService(serviceIntent);

        // Bind the service and grab a reference to the binder.
        //IBinder binder = serviceRule.bindService(serviceIntent);

        // Get the reference to the service, or you can call public methods on the binder directly.

        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getTargetContext();

        assertEquals("com.onyx.m2.relay", appContext.getPackageName());
    }
}
