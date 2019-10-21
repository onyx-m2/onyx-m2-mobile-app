adb root
./adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.onyx.m2.relay

adb shell am startservice com.onyx.m2.relay.RelayService

./adb shell am start-foreground-service com.onyx.m2.relay/.Relay
Service