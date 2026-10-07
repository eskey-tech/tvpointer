# TV Pointer

Android TV app by ESK Tech that turns the TV remote into a mouse pointer, so browsers and other touch-first apps work from the couch.

- **Arrows** move the pointer (hold to speed up). **OK** taps; hold OK for a long press.
- **Push the pointer against a screen edge** to scroll (the arrow key is handed to the app).
- **Toggle button** (Menu by default, changeable in settings) switches the pointer off/on in the current app. It only does this in pointer apps; elsewhere the button keeps its normal job.
- **Back with the on-screen keyboard open** only closes the keyboard.
- Only active in the apps you choose (common browsers are pre-selected). Elsewhere the remote works as normal.

It works through Android's Accessibility Service API: it reads remote button presses, checks which app is in front and whether the keyboard is open, draws the pointer, and presses whatever is under it. Most Android TVs declare no touchscreen, and Android refuses injected taps there, so TV Pointer presses the button or link under the pointer through the accessibility tree; on devices with touch support it injects a real tap. Nothing is stored, collected or sent.

### Precise taps (optional)

Some elements can't be pressed through the accessibility tree because the app doesn't expose them as clickable; Firefox's search/address box is one. **Precise taps** (in TV Pointer's settings) send real taps instead, through the TV's own ADB daemon over network debugging (`127.0.0.1:5555`): TV Pointer connects with its own key, the TV asks once to allow it ("Always allow from this computer"), and taps are then sent as `input tap x y`. This needs Developer options → USB debugging with network debugging on port 5555. The INTERNET permission is used only for this local connection; TV Pointer never connects anywhere else.

Requires Android 8.0+ (Android TV / Google TV).

## Download

Download the APK from the [latest release](https://github.com/eskey-tech/tvpointer/releases/latest), or see [eskey.tech/tvpointer](https://eskey.tech/tvpointer) for setup steps. To install straight on the TV, open a downloader app there and enter `eskey.tech/tvpointer.apk`.

## Privacy

TV Pointer has no accounts, analytics or ads, and collects nothing. Its only network connection is the optional precise-taps link to the TV itself. Full policy: [eskey.tech/tvpointer/privacy](https://eskey.tech/tvpointer/privacy).

## Build

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed if keystore.properties exists
./gradlew bundleRelease      # .aab for Google Play
```

Release signing reads `keystore.properties` in the project root (not committed):

```
storeFile=release.jks
storePassword=...
keyAlias=tvpointer
keyPassword=...
```

The TV banner is generated with `java tools/MakeBanner.java app/src/main/res/drawable-xhdpi/banner.png`.

## TCL TVs

TCL's built-in **TCL Guard** app can block apps from starting in the background. Android then can't restart TV Pointer after an update or reboot, and its settings screen says the service has been stopped. Allow TV Pointer to auto-start in TCL Guard, or over adb:

```sh
adb shell appops set tech.eskey.tvpointer AUTO_START allow
```

## Turning it on

Open TV Pointer, read the notice and choose **Agree and open Accessibility settings**, then turn on TV Pointer.
If a TV hides that screen, it can be enabled over adb. This adds TV Pointer to any services that are already on (such as TalkBack) instead of replacing them:

```sh
current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$current" in ""|null) new=tech.eskey.tvpointer/.PointerService ;;
                   *) new="$current:tech.eskey.tvpointer/.PointerService" ;; esac
adb shell settings put secure enabled_accessibility_services "$new"
adb shell settings put secure accessibility_enabled 1
```

## License

Copyright 2026 ES KEY TECH LTD. Licensed under the [Apache License, Version 2.0](LICENSE).
