# TV Pointer

**Turn your Android TV remote into a mouse.**

TV Pointer puts a pointer on your TV screen: the arrow buttons move it and OK clicks. Web browsers and other apps made for touchscreens become usable from the couch.

Android TV & Google TV · Android 8.0+ · Free and open source · No ads · No tracking

**[Download the APK](https://github.com/eskey-tech/tvpointer/releases/latest/download/tv-pointer.apk)** · [Setup guide](https://eskey.tech/tvpointer) · [Privacy policy](https://eskey.tech/tvpointer/privacy)

## Features

- Arrow buttons move the pointer; hold one down to go faster
- OK clicks; hold OK for a long press
- Push the pointer against a screen edge to scroll
- Back closes the on-screen keyboard without losing what you typed
- Only active in the apps you choose; everywhere else the remote works as normal
- One button switches the pointer off and on (Menu by default)
- Optional precise taps for elements that can't be pressed any other way
- No accounts, ads, analytics or tracking

## Why TV Pointer?

Most Android TVs have no touchscreen, and on those Android refuses simulated taps. TV Pointer is built for that: it presses the button or link under the pointer through Android's accessibility system, and only uses real taps on devices that allow them, or through the optional [precise taps](#precise-taps-optional).

It also stays out of the way. The pointer only appears in the apps you pick (common browsers are pre-selected), and the TV's own menus and apps keep working with the remote as usual.

## Install

1. On the TV, install a downloader app from Google Play (for example *Downloader* by AFTVnews) and enter `eskey.tech/tvpointer.apk`. Or download the APK from the [latest release](https://github.com/eskey-tech/tvpointer/releases/latest) and copy it to the TV with a USB stick.
2. Open TV Pointer.
3. Choose **Agree and open Accessibility settings**.
4. Turn on **TV Pointer**.
5. Open a web browser. The pointer appears in the middle of the screen.

TV Pointer isn't in Accessibility settings, or the service stops? See [Troubleshooting](#troubleshooting).

## Using TV Pointer

| Button | What it does |
| --- | --- |
| Arrows | Move the pointer (hold to speed up) |
| OK | Click; hold for a long press |
| Arrow at a screen edge | Scroll |
| Back, with the keyboard open | Close only the keyboard |
| Menu | Switch the pointer off and on in the current app |

The pointer hides after a few seconds without use; press an arrow or OK to bring it back. In TV Pointer's settings you can choose the apps that use the pointer, change the toggle button, speed and size, and open apps that have no tile on the TV home screen, such as Firefox.

## Precise taps (optional)

Some apps don't expose every element to Android's accessibility system, so TV Pointer can't press them; Firefox's address bar is one. Precise taps send real taps instead, through the TV's own debugging service, which never leaves the TV.

Turn on USB debugging (with network debugging) in Developer options, then turn on **Precise taps** in TV Pointer and choose **Always allow** when the TV asks. Step-by-step instructions: [eskey.tech/tvpointer#precise-taps](https://eskey.tech/tvpointer#precise-taps). The technical details are under [How it works](#how-it-works).

## Supported devices

- Android TV and Google TV, Android 8.0 or later
- Tested on a TCL Android TV running Android 14

## Privacy

TV Pointer has no accounts, ads, analytics or telemetry. It doesn't save or send your button presses, what's on the screen or what you browse, and it never connects to the internet: its only network connection is the optional precise-taps link to the TV itself. Your settings stay on the TV and are removed when you uninstall the app.

Full policy: [eskey.tech/tvpointer/privacy](https://eskey.tech/tvpointer/privacy).

## How it works

TV Pointer is an Android Accessibility Service. It filters remote key events, checks which app is in front and whether the on-screen keyboard is open, and draws the pointer in an accessibility overlay. To click, it finds the deepest element under the pointer in the accessibility tree and performs the click action of the nearest ancestor that has one. On devices that declare touch support it injects a tap gesture instead. Pushing against a screen edge passes the arrow key to the app so the app scrolls itself.

**Precise taps** use the TV's own ADB daemon at `127.0.0.1:5555`. TV Pointer speaks the ADB protocol itself, signs in with an RSA key it generates on the TV (kept in the app's private storage), and runs `input tap x y`, or `input swipe` for a long press. The TV asks once to allow the key. The `INTERNET` permission is used only for this local connection.

## Troubleshooting

### TV Pointer says the service has been stopped (TCL TVs)

TCL's built-in **TCL Guard** app can block apps from starting in the background, so Android can't restart TV Pointer after an update or reboot. Allow TV Pointer to auto-start in TCL Guard, then turn it off and on again in Accessibility settings. Over adb:

```sh
adb shell appops set tech.eskey.tvpointer AUTO_START allow
```

### Accessibility settings don't open, or TV Pointer isn't listed

Open Settings → System (or Device Preferences) → Accessibility. If the TV hides that screen, enable TV Pointer over adb. This adds it to any services that are already on (such as TalkBack) instead of replacing them:

```sh
current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$current" in ""|null) new=tech.eskey.tvpointer/.PointerService ;;
                   *) new="$current:tech.eskey.tvpointer/.PointerService" ;; esac
adb shell settings put secure enabled_accessibility_services "$new"
adb shell settings put secure accessibility_enabled 1
```

### Precise taps won't turn on

Check that USB debugging and network debugging are on in Developer options. If you dismissed the TV's prompt, or later revoked USB debugging authorizations, turn Precise taps off and on again in TV Pointer and choose **Always allow**.

## Development

Java, no dependencies.

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

The TV banner is generated with `java tools/MakeBanner.java app/src/main/res/drawable-xhdpi/banner.png`. Releases attach the APK as `tv-pointer.apk`, the file name the download links point to.

## License

Copyright 2026 ES KEY TECH LTD. Licensed under the [Apache License, Version 2.0](LICENSE).
