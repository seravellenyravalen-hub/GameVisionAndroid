# GameVision Live Screen Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build GameVision as a polished universal Android screen agent: natural-language goals, full-screen understanding, verified gestures, deterministic mathematics, resilient recovery, working authentication, and a premium home experience.

**Architecture:** Extend the existing MediaProjection + Accessibility + Render API. Add bounded live screen state, screen-change intelligence, structured vision state, universal intent/task planning, deterministic math routing, closed-loop gesture verification, recovery, and a redesigned native Android home.

**Tech Stack:** Kotlin Android/XML, MediaProjection/ImageReader, AccessibilityService, Node/Express, PostgreSQL/session state, OpenRouter free models, Render, GitHub Actions/Gradle.

**Spec:** docs/superpowers/specs/2026-09-04-live-screen-companion-design.md

## Global Constraints

- Continue modifying `seravellenyravalen-hub/GameVisionAndroid`; do not start a new project.
- Use the existing Render `gamevision-api` service.
- Keep OpenRouter free-model usage bounded and efficient.
- Never expose the OpenRouter secret.
- Do not permanently store screenshots.
- Do not claim uninterrupted 24/7 operation when Android can stop services.
- Passive Live Screening never performs autonomous actions.
- Authentication must keep signup, login, session validation, logout, and per-user credits working.
- Gestures must use the newest screen state, coordinate calibration, and post-action verification.
- Supported mathematics must use deterministic computation and verification rather than trusting an LLM calculation.

---

### Task 1: Finalize the design spec
**Files:** `docs/superpowers/specs/2026-09-04-live-screen-companion-design.md`
- [ ] Add universal natural-language intent, screen+conversation context, tool routing, deterministic math, gesture calibration, recovery, capability awareness, privacy, credits, fallback AI, and premium home requirements.
- [ ] Self-review for conflicts with passive observation/command-driven automation.
- [ ] Commit: `docs: finalize universal gamevision agent scope`.

### Task 2: Protect and verify authentication
**Files:** `AuthActivity.kt`, `MainActivity.kt`, `GameVisionApplication.kt`, `AuthStore.kt` as required; auth tests.
- [ ] Add tests for canonical backend URL and session state.
- [ ] Replace stale Railway defaults with the live Render URL through one canonical configuration path.
- [ ] Preserve signup/login/me/logout and invalid-session clearing.
- [ ] Ensure temporary server failure is not silently treated as valid authentication.
- [ ] Run Android tests and commit: `fix: keep android authentication on render backend`.

### Task 3: Live frame/session intelligence
**Files:** `backend/src/frameStore.js`, `backend/src/server.js`, frame-store tests.
- [ ] Test per-user isolation, duplicate/change detection, bounded transient state, timestamps, dimensions, epochs, and stale rejection.
- [ ] Implement newest-frame state and lightweight change fingerprints without permanent screenshot storage.
- [ ] Expose current frame/epoch/freshness needed by Android.
- [ ] Run backend tests and commit: `feat: add live screen intelligence state`.

### Task 4: Full-screen vision and screen map
**Files:** existing vision/planner module, new screen-state module, backend tests.
- [ ] Test structured full-screen inventory, target coordinates, confidence, and malformed model output.
- [ ] Implement strict screen-state parsing with full frame as authority and crops only as supplemental evidence.
- [ ] Add debounced/rate-limited live analysis so unchanged frames do not waste free-model calls.
- [ ] Run tests and commit: `feat: add structured screen understanding`.

### Task 5: Universal intent and task planner
**Files:** new/updated task planner, `backend/src/server.js`, planner tests.
- [ ] Test natural commands, screen questions, multi-step actions, math requests, and ambiguity.
- [ ] Implement intent/tool routing across vision, math, current-information lookup where available, Android actions, and explanations.
- [ ] Enforce bounded plans, confidence/uncertainty, newest-frame grounding, and bounded replanning.
- [ ] Run tests and commit: `feat: add universal task planner`.

### Task 6: Deterministic mathematics engine
**Files:** `backend/src/mathEngine.js`, `backend/src/mathEngine.test.js`, task planner.
- [ ] Test arithmetic, percentages, ratios, algebra, simultaneous equations, powers/roots, statistics, units, and supported calculus/geometry.
- [ ] Implement deterministic evaluation for supported classes with exact/approximate results and working steps where possible.
- [ ] Add independent result verification and explicit unsupported/ambiguous responses.
- [ ] Route screen-derived equations through vision/OCR extraction.
- [ ] Run tests and commit: `feat: add deterministic math reasoning`.

### Task 7: Robust gesture engine
**Files:** `AutomationController.kt`, `GameVisionAccessibilityService.kt` if required, action planner, automation tests.
- [ ] Test tap, double tap, long press, vertical/horizontal swipe, drag, pinch, two-finger swipe, typing, Back/Home/Recents across differing dimensions/scales.
- [ ] Implement normalized-to-display coordinate calibration and safe bounds.
- [ ] Harden gesture timing and multi-touch construction within Accessibility limits.
- [ ] Require a newer frame after every action and verify expected visual state.
- [ ] On failure, re-read/replan rather than replay stale coordinates.
- [ ] Run tests and commit: `feat: harden verified gesture automation`.

### Task 8: Memory, interruption recovery, capability awareness
**Files:** backend task/session state, Android controller/HUD integration, recovery tests.
- [ ] Test unexpected dialogs, loading states, navigation changes, backend epoch changes, service interruptions, and missing permissions.
- [ ] Reconstruct task state from the current screen instead of old coordinates.
- [ ] Report blocked secure actions/missing permissions honestly.
- [ ] Bound retries and safely stop repeated genuine failures.
- [ ] Run tests and commit: `feat: add resilient task recovery`.

### Task 9: Premium home-page rebuild
**Files:** `activity_main.xml`, `MainActivity.kt`, related drawable/value resources, UI/state tests.
- [ ] Test logged-out, AI-offline, monitoring-off, monitoring-live, low-credit, and permission-missing states.
- [ ] Build a polished first screen with strong hero/status, primary Live Vision action, natural-language assistant entry, Monitor/Assistant/Auto capabilities, live status, recent activity, account/credits, and permission guidance.
- [ ] Keep controls touch-safe and navigation clear on phone screens.
- [ ] Preserve sign-in as the gate into Home.
- [ ] Build/test and commit: `feat: rebuild gamevision home experience`.

### Task 10: Intelligent live HUD
**Files:** `MonitorService.kt`, `AssistantOverlayService.kt`, HUD resources, HUD tests.
- [ ] Test `LIVE`, `ANALYZING`, `READY`, `ACTING`, `VERIFYING`, `SUCCESS`, `RECOVERING`, and `OFFLINE` mappings.
- [ ] Show current-screen summary, freshness/confidence, task/action state, and useful diagnostics without blocking the underlying app.
- [ ] Run tests/build and commit: `feat: add intelligent live hud`.

### Task 11: End-to-end account, credits, fallback, and security
**Files:** existing auth/credit/session modules only where needed; integration tests.
- [ ] Test user isolation, credits/exhaustion/reset behavior already defined by backend, and AI fallback.
- [ ] Enforce per-user live state and bounded AI consumption.
- [ ] Confirm secrets remain server-side and never appear in Android UI/logs/responses.
- [ ] Run complete backend and Android suites and commit: `test: verify universal agent integration`.

### Task 12: Final verification and release
**Files:** CI/config only for real failures.
- [ ] Run backend tests, Android unit tests, and Gradle APK build.
- [ ] Confirm GitHub Actions passes on the final `main` commit and capture the artifact.
- [ ] Confirm Render deploys the exact same commit and `/health` reports healthy/auth/AI readiness.
- [ ] Verify deployed authentication endpoints without exposing credentials.
- [ ] Verify final APK artifact and repository/deployment status.
- [ ] If a verification failure requires a fix, fix it, rerun affected checks, and only then claim success.
