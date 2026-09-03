# Active GameVision Agent Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make GameVision an active conversational agent that does not fail normal chat because a screen frame is stale, can continuously execute user-authorized commands, and exposes a broader set of Android gestures/global actions.

**Architecture:** Separate conversational reasoning from visual-state availability. `/api/ask` may answer text-only requests without a current frame and uses the latest frame opportunistically for screen-dependent requests. Autonomous control uses a frame generation/sequence handshake so the client waits for a capture newer than the action that triggered it, then asks for the next decision. Accessibility execution supports touch gestures plus Android global actions that the OS exposes to an accessibility service.

**Tech Stack:** Kotlin Android, MediaProjection/ImageReader, AccessibilityService/dispatchGesture/performGlobalAction, Node.js/Express, OpenAI Responses API, Gemini API, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-03-general-game-agent-design.md`

## Global Constraints

- GameVision remains game-agnostic and conversational.
- User explicitly enables AUTO and AccessibilityService.
- Screen capture remains user-approved MediaProjection.
- No hidden game state or fabricated completion claims.
- Every autonomous gesture is followed by fresh-screen verification.
- OpenAI remains primary and Gemini remains fallback.

---

### Task 1: Regression tests for active routing and gesture contracts

**Files:**
- Modify: `backend/src/assistant.test.js`
- Modify: `backend/src/assistant.js`
- Add if needed: focused backend routing tests

- [ ] Add failing tests that ordinary conversation does not require a fresh frame.
- [ ] Add failing tests for visual/action requests being classified as screen-dependent.
- [ ] Add failing tests for expanded action types: DOUBLE_TAP, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS.
- [ ] Run backend tests and observe the expected failures.

### Task 2: Remove stale-frame dead end from conversational chat

**Files:**
- Modify: `backend/src/assistant.js`
- Modify: `backend/src/aiProviders.js`
- Modify: `backend/src/server.js`

- [ ] Add text-only provider request support for OpenAI and Gemini.
- [ ] Make `/api/ask` use a fresh frame when available, but answer ordinary requests without requiring one.
- [ ] For screen-dependent requests with no fresh frame, return a machine-readable `FRAME_NEEDED` response rather than the generic "can't answer" failure.
- [ ] Add bounded retry-friendly metadata such as frame age and whether vision was used.
- [ ] Run backend tests.

### Task 3: Reliable frame sequencing for automation

**Files:**
- Modify: `backend/src/server.js`
- Modify: `app/src/main/java/com/gamevision/companion/MonitorService.kt`
- Modify: `app/src/main/java/com/gamevision/companion/AutomationController.kt`

- [ ] Assign every uploaded frame set a monotonically increasing sequence number and capture timestamp.
- [ ] Return the sequence number from `/api/analyze-frame` and expose latest-frame metadata through a lightweight endpoint.
- [ ] Let automation request a decision only after a frame exists and, after an action, after a sequence newer than the previous decision is observed.
- [ ] Replace fixed sleeps with bounded polling/backoff for a genuinely fresh frame.
- [ ] Make the monitor prioritize a fresh upload when automation is waiting instead of being blocked by the 1.5-second cadence.
- [ ] Keep failures retryable and stop only after repeated infrastructure failures.
- [ ] Compile the Android app and run backend tests.

### Task 4: Expand Android action/gesture capability

**Files:**
- Modify: `backend/src/assistant.js`
- Modify: `backend/src/aiProviders.js`
- Modify: `app/src/main/java/com/gamevision/companion/GameVisionAccessibilityService.kt`
- Modify: `app/src/main/java/com/gamevision/companion/AutomationController.kt`

- [ ] Add DOUBLE_TAP and optional multi-stroke touch actions where reliable.
- [ ] Add BACK, HOME, RECENTS, NOTIFICATIONS and QUICK_SETTINGS global actions.
- [ ] Check `getSystemActions()` before global actions and report unavailable actions accurately.
- [ ] Keep normalized coordinates for coordinate gestures.
- [ ] Return explicit Android success/cancellation/unavailable results.
- [ ] Compile the Android app.

### Task 5: Active conversation and status behavior

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/AssistantOverlayService.kt`

- [ ] Distinguish CHAT, WAITING FOR SCREEN, WORKING, VERIFYING, DONE, and ERROR states.
- [ ] Do not display internal stale-frame errors as the final assistant response.
- [ ] Keep typed and spoken commands on the same active-agent pipeline.
- [ ] Allow AUTO to continue until completion, user STOP, or a genuine blocker.
- [ ] Report each action and verification result in the conversation/status area.
- [ ] Compile the Android app.

### Task 6: CI and deployment verification

- [ ] Run the backend test suite.
- [ ] Run GitHub Actions Android build.
- [ ] Verify the APK artifact is produced.
- [ ] Verify the latest backend deployment uses the new commit and reports healthy provider configuration.
- [ ] Inspect logs/status before reporting the work complete.
