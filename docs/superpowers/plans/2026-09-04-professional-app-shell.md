# GameVision Professional App Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Android companion into a polished product-style GameVision app while preserving the working screen capture, AI assistant, voice, HUD, and Accessibility automation layers.

**Architecture:** Keep the existing single Android activity and execution services, but replace the developer/setup-heavy main layout with five product sections: Home, Assistant, Monitor, Activity, and Settings. The backend endpoint remains an internal constant and is never rendered as user-editable configuration. Account-based quotas will be added only with durable server-side identity and storage; the app will not fake per-user credits in local state.

**Tech Stack:** Kotlin Android views, MediaProjection, AccessibilityService, SpeechRecognizer/TTS, Node.js/Express, OpenRouter free-only routing, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-04-professional-app-shell-design.md`

## Global Constraints
- No backend URL in normal user-facing UI.
- No raw OpenRouter/provider implementation details in normal product UI.
- Preserve user-approved MediaProjection and Android permission boundaries.
- Preserve free-only AI routing and do not claim unlimited free inference.
- Preserve explicit AUTO control and a prominent STOP path.
- Never claim an Android action succeeded without an underlying success result.
- Version must advance beyond 1.0.0.

---

### Task 1: Version and CI test gate

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/android-build.yml`
- Test: `app/src/test/java/com/gamevision/companion/VersionTest.kt`

- [x] Add a failing unit test requiring a version newer than 1.0.0.
- [x] Add JUnit dependency and run Android unit tests in CI.
- [ ] Set versionCode to 2 and versionName to 1.1.0.
- [ ] Run unit tests and confirm PASS.

### Task 2: Product app shell

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/gamevision/companion/MainActivity.kt`
- Create: `app/src/main/res/drawable/ic_nav_home.xml`
- Create: `app/src/main/res/drawable/ic_nav_assistant.xml`
- Create: `app/src/main/res/drawable/ic_nav_monitor.xml`
- Create: `app/src/main/res/drawable/ic_nav_activity.xml`
- Create: `app/src/main/res/drawable/ic_nav_settings.xml`

- [ ] Remove the visible server URL field.
- [ ] Add five-section navigation with professional product copy.
- [ ] Add Home quick actions for Monitor, Assistant, and Auto Control.
- [ ] Add dedicated Monitor controls while keeping Start disabled during active monitoring.
- [ ] Add Assistant entry point that opens the existing floating assistant.
- [ ] Add lightweight session Activity view.
- [ ] Add Settings sections for permissions, assistant, automation, appearance, and app version.
- [ ] Keep all Android settings flows explicit and user-controlled.

### Task 3: Account/quota foundation

**Files:**
- Create: backend identity, session, quota, and persistence modules after selecting a durable free-tier datastore.
- Modify: `backend/src/server.js`
- Modify: Android networking/auth state.

- [ ] Define account/session contract with short-lived access tokens and server-side password hashing or a supported identity provider.
- [ ] Store per-account daily AI credits server-side rather than in the APK.
- [ ] Reset credit buckets automatically by UTC day/window without requiring model switching.
- [ ] Enforce quota atomically before expensive AI calls.
- [ ] Expose only safe usage metadata to the app.
- [ ] Add sign-in/sign-up UI only after the backend datastore and secret configuration are verified.

### Task 4: Active-agent reliability

**Files:** existing assistant, automation, and monitor services.

- [ ] Continue improving voice recognition recovery and partial-result handling.
- [ ] Route imperative commands to AUTO execution when appropriate.
- [ ] Keep fresh-frame verification after each action.
- [ ] Add structured activity events for command, action, verification, and failure states.
- [ ] Add regression tests for stale frames, provider errors, and action normalization.

### Task 5: Release verification

- [ ] Run backend tests.
- [ ] Run Android unit tests.
- [ ] Run `assembleDebug`.
- [ ] Inspect APK artifact and version metadata.
- [ ] Verify no backend URL appears in `activity_main.xml` or normal app copy.
- [ ] Verify GitHub Actions succeeds before providing a new APK.
