# GameVision Android Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Kotlin Android companion that performs user-approved MediaProjection capture, sends visible frames to GameVision, and renders a floating HUD.

**Architecture:** A standalone Android application complements the existing AppDeploy dashboard. A foreground service owns MediaProjection/ImageReader capture, HTTP frame upload, TTS, and TYPE_APPLICATION_OVERLAY HUD lifecycle.

**Tech Stack:** Kotlin, Android SDK, MediaProjection, ImageReader, foreground service, WindowManager overlay, Android TextToSpeech, HttpURLConnection, org.json.

**Spec:** `docs/superpowers/specs/2026-09-02-gamevision-android-companion-design.md`

## Global Constraints
- Screen capture requires explicit Android MediaProjection consent.
- Target Android 36 and support Android 8.0+.
- Use `mediaProjection` foreground-service type and `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission.
- Overlay requires explicit `SYSTEM_ALERT_WINDOW` authorization.
- Send only user-visible captured frames to the configured `/api/analyze-frame` endpoint.
- Do not access hidden game/server data or automate gameplay.

---

### Task 1: Android project and permission surface

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/styles.xml`
- Create: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1:** Configure the application namespace, application id, SDK levels, and Kotlin/Android plugins.
- [ ] **Step 2:** Declare `INTERNET`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, and notification permissions.
- [ ] **Step 3:** Declare `MonitorService` with `android:foregroundServiceType="mediaProjection"` and an exported launcher activity.
- [ ] **Step 4:** Add a minimal mobile control screen with server URL, overlay permission, start, stop, HUD, and status controls.

### Task 2: MediaProjection foreground service

**Files:**
- Create: `app/src/main/java/com/gamevision/companion/MonitorService.kt`

- [ ] **Step 1:** Create the low-importance foreground notification channel.
- [ ] **Step 2:** Start the service with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` before retrieving the projection token.
- [ ] **Step 3:** Create an `ImageReader`, `VirtualDisplay`, and projection callback.
- [ ] **Step 4:** Convert RGBA frames to bounded JPEG images.
- [ ] **Step 5:** Upload at most one frame per 1.5 seconds to `/api/analyze-frame`.
- [ ] **Step 6:** Parse analysis confidence, verification, score, and notes.
- [ ] **Step 7:** Stop and release projection, virtual display, reader, executor, overlay, and notification resources when capture ends.

### Task 3: Floating HUD and voice

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/MonitorService.kt`

- [ ] **Step 1:** Check `Settings.canDrawOverlays()` before adding a `TYPE_APPLICATION_OVERLAY` view.
- [ ] **Step 2:** Render a compact non-focusable, non-touchable HUD in the top-right corner.
- [ ] **Step 3:** Update the HUD from analysis results and show upload failures.
- [ ] **Step 4:** Initialize Android TextToSpeech and speak the first analysis note when available.
- [ ] **Step 5:** Remove the HUD and shut down TTS on service destruction.

### Task 4: Activity integration and Android 14+ capture lifecycle

**Files:**
- Create: `app/src/main/java/com/gamevision/companion/MainActivity.kt`

- [ ] **Step 1:** Launch `MediaProjectionManager.createScreenCaptureIntent()` from the user Start action.
- [ ] **Step 2:** Pass the approved result code/data and configured server URL into the foreground service.
- [ ] **Step 3:** Open the system overlay settings when overlay permission is missing.
- [ ] **Step 4:** Stop the service from the Stop action and expose cancellation state.

### Task 5: Web dashboard companion entry point

**Files:**
- Modify: existing GameVision `src/App.tsx`
- Modify: existing GameVision `tests/tests.txt`

- [ ] **Step 1:** Add a Native Companion navigation entry and setup panel explaining why Android browser capture cannot replace MediaProjection.
- [ ] **Step 2:** Display the companion's required permissions and the existing GameVision server endpoint without exposing secrets.
- [ ] **Step 3:** Add an Android companion workflow test covering setup guidance and permission requirements.

### Verification

- [ ] Run Android lint/build in an environment with Android SDK 36 and Android Gradle Plugin 8.13.
- [ ] Install on Android 15 test hardware.
- [ ] Verify capture consent appears and cancelling it leaves the service stopped.
- [ ] Verify foreground notification remains while monitoring.
- [ ] Verify game screen frames reach `/api/analyze-frame` and analysis appears in the HUD.
- [ ] Verify overlay permission controls HUD visibility.
- [ ] Verify Stop monitoring releases projection and removes HUD.
- [ ] Run existing GameVision AppDeploy QA after the web dashboard integration.
