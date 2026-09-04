# GameVision Companion

Native Android companion for the existing GameVision Monitor web dashboard.

## Current release: 1.2.3
- Fast local routing for simple commands such as tap, type, back, home, and directional scroll.
- Complex commands use the free-only OpenRouter action planner on the backend.
- Every executed action waits for a fresh captured frame before continuing or reporting success.
- Bounded recovery retries failed actions or missing screen changes and stops safely after the recovery budget is exhausted.
- The assistant exposes THINKING, ACTING, VERIFYING, RECOVERING, SUCCESS, and FAILED states.
- OpenRouter failures are surfaced with safe actionable error codes instead of one generic unavailable message.
- The OpenRouter API key remains server-side in Render; it is never stored in the APK.
- Passive monitoring never starts automation. Actions require an explicit user command.

## What it does
- Uses Android MediaProjection only after the user approves screen capture.
- Runs capture in a visible mediaProjection foreground service.
- Sends bounded JPEG frames to the configured GameVision `/api/analyze-frame` endpoint.
- Displays analysis in an Android floating HUD after the user grants overlay permission.
- Provides an optional floating Live Assistant with text and voice input.
- Uses Android Accessibility only for actions explicitly initiated through the assistant.

## Build
Open this directory in Android Studio with an Android SDK that supports API 36, then sync and build the `app` module. GitHub Actions also runs backend tests, Android unit tests, and produces `GameVision.apk`.

## Device setup
1. Install the APK.
2. Open GameVision Companion.
3. Confirm the GameVision server URL.
4. Grant **Display over other apps** for the floating HUD/assistant.
5. Enable **GameVision Accessibility** when you want command execution.
6. Tap **Start monitoring**.
7. Approve Android's screen-capture dialog.
8. Switch to the authorized app/game.
9. Give the assistant an explicit command, then watch the status move through execution and verification.
10. Stop monitoring when finished.

## Safety boundary
This companion processes only visible pixels from the user's approved screen capture. It does not read game memory, hidden server data, credentials, or perform autonomous actions from passive monitoring. Accessibility actions are initiated by explicit user commands and are bounded by the command controller.

## Backend
The production Android client uses `https://gamevision-api.onrender.com`. Keep `OPENROUTER_API_KEY` configured only in the Render service environment. The backend is intentionally free-only and preserves user credits when an upstream AI request fails.
