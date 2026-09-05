# Multi-Provider Live Vision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make GameVision genuinely live while using Gemini, OpenAI, and OpenRouter as a resilient provider pool so free AI quotas are preserved instead of being exhausted by frame/retry churn.

**Architecture:** Continuous MediaProjection capture feeds a local newest-frame buffer independently from network upload. AI requests use one configured provider at a time with rotation and failover. One user automation session consumes one app allowance unit rather than one unit per observe/decide/verify step.

**Tech Stack:** Kotlin Android MediaProjection/ImageReader/AccessibilityService; Node.js/Express; PostgreSQL; Gemini REST; OpenAI Responses API; OpenRouter chat completions.

**Spec:** `docs/superpowers/specs/2026-09-05-multi-provider-live-vision-design.md`

## Global Constraints

- Free-first operation; never expose provider keys to Android.
- Continuous local frame capture must not be blocked by network I/O.
- Simple supported commands remain local and should not consume AI allowance.
- Normal AI operation uses one provider at a time with rotation/failover; do not call all three for every request.
- Authentication remains required for AI and frame endpoints.
- MediaProjection remains user-consented and Android-controlled.

---

### Task 1: Lock live cadence behavior with tests

**Files:**
- Modify: `app/src/test/java/com/gamevision/companion/LiveVisionPolicyTest.kt`
- Modify: `app/src/main/java/com/gamevision/companion/LiveVisionPolicy.kt`

- [ ] Write tests for bounded capture cadence, changed-frame upload cadence, heartbeat upload, and no duplicate upload flood.
- [ ] Run Android unit tests and confirm the new policy tests fail against the current broken policy where appropriate.
- [ ] Implement minimal policy state for capture/upload timing and fingerprint tracking.
- [ ] Run the tests again and confirm they pass.
- [ ] Commit the policy fix.

### Task 2: Separate live capture from network upload

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/MonitorService.kt`
- Modify/Create: `app/src/main/java/com/gamevision/companion/ForegroundState.kt` if needed
- Modify: `app/src/main/java/com/gamevision/companion/GameVisionApplication.kt` if needed

- [ ] Add a dedicated upload executor or equivalent latest-only uploader so slow HTTP cannot stall frame acquisition/encoding.
- [ ] Keep `acquireLatestImage()` and continuous local frame updates.
- [ ] Make pending network work latest-only rather than queueing stale frames.
- [ ] Skip GameVision's own foreground Activity as a source frame without destroying the authorized projection session.
- [ ] Preserve the last valid external frame for diagnostics while GameVision UI is foreground.
- [ ] Add/adjust tests or compile-time checks for the lifecycle guard.
- [ ] Commit the live pipeline fix.

### Task 3: Add the three-provider server pool

**Files:**
- Modify: `backend/src/aiProviders.js`
- Modify: `backend/src/aiProviders.test.js`
- Modify: `backend/src/server.js`
- Modify: `backend/package.json` only if a dependency is genuinely required (prefer REST fetch to avoid new dependencies)

- [ ] Write failing provider tests for configuration detection, provider ordering/rotation, cooldown/failover, and normalized JSON responses.
- [ ] Implement Gemini REST vision calls using the server-side `GEMINI_API_KEY` and a configurable free/fast model.
- [ ] Implement OpenAI Responses API vision calls using the server-side `OPENAI_API_KEY` and configurable model.
- [ ] Preserve OpenRouter free-model rotation and server-side `OPENROUTER_API_KEY`.
- [ ] Implement a provider pool that selects one provider per operation, rotates after successful calls, and fails over on retryable/provider-unavailable errors.
- [ ] Return provider metadata for diagnostics without exposing secrets.
- [ ] Run backend tests and confirm all provider tests pass.
- [ ] Commit the provider-pool implementation.

### Task 4: Stop allowance multiplication inside automation sessions

**Files:**
- Modify: `backend/src/auth.js`
- Modify: `backend/src/server.js`
- Modify: `backend/src/auth.test.js`
- Modify: `backend/src/assistant.test.js` as needed
- Modify: `app/src/main/java/com/gamevision/companion/AutomationController.kt`

- [ ] Write failing tests demonstrating that repeated automation decisions for one session do not consume a fresh allowance unit each time.
- [ ] Add a short-lived server-side automation session/lease tied to authenticated user and goal/session token.
- [ ] Reserve one allowance unit at session start; subsequent decisions in that bounded session reuse the reservation.
- [ ] Refund the reservation only when the session fails before useful AI work is completed or when the provider pool cannot serve the request.
- [ ] Ensure normal `/api/ask` requests still consume one unit per independent AI request.
- [ ] Ensure fast local commands consume no AI allowance.
- [ ] Pass the automation session identifier from Android on subsequent decide calls.
- [ ] Run auth/backend tests and verify concurrent requests cannot double-spend the same reservation.
- [ ] Commit the allowance/session fix.

### Task 5: Make AI vision use the newest frame without continuous inference

**Files:**
- Modify: `backend/src/server.js`
- Modify: `backend/src/frameStore.js` only if needed
- Modify: `app/src/main/java/com/gamevision/companion/AutomationController.kt`
- Modify: `app/src/main/java/com/gamevision/companion/MainActivity.kt` only if UI status needs adjustment

- [ ] Ensure every AI decision consumes the newest fresh frame, not an older queued frame.
- [ ] Ensure verification waits for a newer sequence/epoch after an action.
- [ ] Keep Watch mode observation debounced rather than invoking AI on every frame.
- [ ] Keep fast/local command execution ahead of cloud AI.
- [ ] Add regression tests for fresh-frame selection and session behavior.
- [ ] Commit the integration fix.

### Task 6: Full verification and APK

**Files:**
- Modify only if verification exposes a defect.

- [ ] Run backend `npm test`.
- [ ] Run Android `gradle --no-daemon testDebugUnitTest`.
- [ ] Run Android `gradle --no-daemon assembleDebug`.
- [ ] Confirm GitHub Actions completes successfully and produces the `GameVision` APK artifact.
- [ ] Review the resulting commit/status before claiming completion.
- [ ] Download/provide the verified APK artifact and give a real-device test checklist covering sign-in, continuous monitoring, live frame freshness, tap execution, provider failover, and credit behavior.
