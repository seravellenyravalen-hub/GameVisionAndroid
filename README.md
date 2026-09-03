# GameVision Companion

Native Android companion for the existing GameVision Monitor web dashboard.

## What it does
- Uses Android MediaProjection only after the user approves screen capture.
- Runs capture in a visible mediaProjection foreground service.
- Sends bounded JPEG frames to the configured GameVision `/api/analyze-frame` endpoint.
- Displays analysis in an Android floating HUD after the user grants overlay permission.
- Uses Android Text-to-Speech for analysis notes.

## Build
Open this directory in Android Studio with an Android SDK that supports API 36, then sync and build the `app` module.

The current ChatGPT execution environment does not include an Android SDK/emulator, so an installable APK cannot honestly be claimed as compiled here. The source project is complete enough for Android Studio/CI to build and should be device-tested on Android 15 before release.

## Device setup
1. Install the APK.
2. Open GameVision Companion.
3. Confirm the GameVision server URL.
4. Grant **Display over other apps** if you want the floating HUD.
5. Tap **Start monitoring**.
6. Approve Android's screen-capture dialog.
7. Switch to the authorized game.
8. Stop monitoring from the companion when finished.

## Safety boundary
This companion only processes visible pixels from the user's approved screen capture. It does not read game memory, hidden server data, credentials, or automate gameplay.
