# GameVision Live Screen Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build GameVision as a polished universal Android screen agent with fast authentication, near-instant simple actions, continuous user-controlled voice/live mode, full-screen understanding, verified gestures, deterministic mathematics, resilient recovery, and a premium home experience.

**Architecture:** Keep MediaProjection + Accessibility + Render API, but add a latency-aware command router. Simple deterministic commands use the freshest local screen state without an unnecessary LLM round trip; complex tasks use the vision/planner path. A user-controlled draggable mic enables continuous listen/respond mode, while passive observation never performs actions without an explicit user command.

**Tech Stack:** Kotlin Android/XML, MediaProjection/ImageReader, AccessibilityService, SpeechRecognizer/TTS, Node/Express, PostgreSQL/session state, OpenRouter free models, Render, GitHub Actions/Gradle.

**Spec:** docs/superpowers/specs/2026-09-04-live-screen-companion-design.md

## Global Constraints
- Continue modifying `seravellenyravalen-hub/GameVisionAndroid`; do not start a new project.
- Use the existing Render `gamevision-api` service.
- Keep OpenRouter free-model usage bounded and efficient.
- Never expose the OpenRouter secret.
- Do not permanently store screenshots.
- Do not claim uninterrupted 24/7 operation when Android can stop services.
- Passive Live Screening and continuous listening never perform autonomous actions; an explicit user command is required.
- Authentication must keep signup, login, session validation, logout, and per-user credits working.
- Gestures must use the newest screen state, coordinate calibration, and post-action verification.
- Supported mathematics must use deterministic computation and verification rather than trusting an LLM calculation.
- Voice mode must be user-started, user-stoppable, visible, and permission-aware.
- Optimize for the shortest reliable path; do not trade verification/security for raw speed.

---

### Task 1: Fast authentication
**Files:** `AuthActivity.kt`, `AuthStore.kt`, `GameVisionApplication.kt`, `backend/src/auth.js`, `backend/src/server.js`, auth tests.
- [ ] Test that signup creates and returns a session in one backend flow.
- [ ] Test session validation and token reuse without unnecessary round trips.
- [ ] Implement signup-session creation without signup-then-login duplication.
- [ ] Keep existing-session launch fast when the token is locally present while still validating safely in the background.
- [ ] Normalize all Android/backend URLs to Render.
- [ ] Run tests.

### Task 2: Fast command router
**Files:** new/updated intent router, `AssistantOverlayService.kt`, `AutomationController.kt`, backend tests.
- [ ] Test deterministic parsing for tap/click, double tap, long press, back/home/recents, type, swipe, drag, wait, and simple navigation commands.
- [ ] Test that simple commands do not call OpenRouter when a fresh compatible screen state is already available.
- [ ] Implement a fast path with strict target-confidence/bounds checks.
- [ ] Fall back to vision/planner for ambiguous, multi-step, or screen-dependent reasoning.
- [ ] Cancel/ignore stale command responses when a newer command arrives.
- [ ] Keep fresh-frame verification after every action.

### Task 3: Live frame/session intelligence
**Files:** `backend/src/frameStore.js`, `backend/src/server.js`, frame-store tests.
- [ ] Test per-user isolation, duplicate/change detection, bounded transient state, timestamps, dimensions, epochs, and stale rejection.
- [ ] Implement newest-frame state and lightweight change fingerprints without permanent screenshot storage.
- [ ] Expose current frame/epoch/freshness needed by Android.

### Task 4: Full-screen vision and screen map
**Files:** existing vision/planner module, new screen-state module, backend tests.
- [ ] Test structured full-screen inventory, target coordinates, confidence, and malformed model output.
- [ ] Implement strict screen-state parsing with full frame as authority and crops only as supplemental evidence.
- [ ] Add debounced/rate-limited live analysis so unchanged frames do not waste free-model calls.

### Task 5: Universal intent and task planner
**Files:** new/updated task planner, `backend/src/server.js`, planner tests.
- [ ] Test natural commands, screen questions, multi-step actions, math requests, and ambiguity.
- [ ] Implement intent/tool routing across vision, math, Android actions, and explanations.
- [ ] Enforce bounded plans, confidence/uncertainty, newest-frame grounding, and bounded replanning.

### Task 6: Deterministic mathematics engine
**Files:** `backend/src/mathEngine.js`, `backend/src/mathEngine.test.js`, task planner.
- [ ] Test arithmetic, percentages, ratios, algebra, simultaneous equations, powers/roots, statistics, units, and supported calculus/geometry.
- [ ] Implement deterministic evaluation and independent result verification.
- [ ] Route screen-derived equations through vision/OCR extraction.

### Task 7: Robust gesture engine
**Files:** `AutomationController.kt`, `GameVisionAccessibilityService.kt`, action planner, automation tests.
- [ ] Test tap, double tap, long press, vertical/horizontal swipe, drag, pinch, two-finger swipe, typing, Back/Home/Recents across differing dimensions/scales.
- [ ] Implement normalized-to-display coordinate calibration and safe bounds.
- [ ] Require a newer frame after every action and verify expected visual state.
- [ ] On failure, re-read/replan rather than replay stale coordinates.

### Task 8: Voice/live conversation mode
**Files:** `AssistantOverlayService.kt`, voice helper/tests, HUD resources.
- [ ] Test user-controlled start/stop and repeated recognition sessions.
- [ ] Make the mic control draggable with the floating assistant and preserve a safe screen position.
- [ ] Add a clearly visible `LIVE LISTENING` state and stop control.
- [ ] Support hands-free conversation turn-taking: listen → transcribe → answer/act → optional TTS → listen again.
- [ ] Use on-device recognition when available, fall back to Android speech recognition otherwise.
- [ ] Handle silence, errors, permission loss, app/service interruption, and cancellation safely.
- [ ] Never interpret background speech as an action unless it is part of the user-started live session and recognized as a command.

### Task 9: Memory, interruption recovery, capability awareness
**Files:** backend task/session state, Android controller/HUD integration, recovery tests.
- [ ] Test unexpected dialogs, loading states, navigation changes, backend epoch changes, service interruptions, and missing permissions.
- [ ] Reconstruct task state from the current screen instead of old coordinates.
- [ ] Report blocked secure actions/missing permissions honestly.
- [ ] Bound retries and safely stop repeated genuine failures.

### Task 10: Premium home-page rebuild
**Files:** `activity_main.xml`, `MainActivity.kt`, related drawable/value resources, UI/state tests.
- [ ] Test logged-out, AI-offline, monitoring-off, monitoring-live, low-credit, and permission-missing states.
- [ ] Build a polished first screen with Live Vision, assistant, voice/live mode, Monitor/Auto capabilities, live status, recent activity, account/credits, and permission guidance.
- [ ] Preserve sign-in as the gate into Home.

### Task 11: Intelligent live HUD and performance telemetry
**Files:** `MonitorService.kt`, `AssistantOverlayService.kt`, HUD resources, performance tests.
- [ ] Test `READY`, `LISTENING`, `ANALYZING`, `ACTING`, `VERIFYING`, `SUCCESS`, `RECOVERING`, and `OFFLINE` states.
- [ ] Show frame freshness/confidence and action state without blocking the underlying app.
- [ ] Record bounded client-side timing metrics for auth, command routing, action, verification, and AI latency without storing screen content.

### Task 12: End-to-end account, credits, fallback, and security
**Files:** existing auth/credit/session modules only where needed; integration tests.
- [ ] Test user isolation, credits/exhaustion/reset behavior, AI fallback, and voice/live command boundaries.
- [ ] Enforce per-user live state and bounded AI consumption.
- [ ] Confirm secrets remain server-side and never appear in Android UI/logs/responses.

### Task 13: Final verification and release
**Files:** CI/config only for real failures.
- [ ] Run backend tests, Android unit tests, and Gradle APK build.
- [ ] Confirm GitHub Actions passes on the final commit and capture the artifact.
- [ ] Confirm Render deploys the exact same commit and `/health` reports healthy/auth/AI readiness.
- [ ] Verify deployed authentication endpoints without exposing credentials.
- [ ] Verify final APK artifact and repository/deployment status.
