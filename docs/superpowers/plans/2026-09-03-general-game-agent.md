# General Game Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert GameVision from sports-oriented screen analysis into a generic conversational visual game agent with full-screen understanding and opt-in autonomous touch control.

**Architecture:** Android captures the complete display, creates overlapping high-resolution regions, and uploads a frame set. The backend keeps the latest frame set and exposes generic chat and one-step action-decision APIs. Android executes approved action plans locally through an explicitly enabled AccessibilityService, captures the result, and repeats only after verification.

**Tech Stack:** Kotlin Android services, MediaProjection, AccessibilityService, JavaScript/Node.js Express, OpenAI Responses API, Gemini API, JSON schemas, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-03-general-game-agent-design.md`

## Global Constraints

- The AI must be game-agnostic; no football-specific fields or prompts.
- Screen capture remains user-approved MediaProjection.
- Autonomous control is opt-in and must have a prominent stop control.
- Accessibility gestures require explicit user enablement.
- Actions use normalized full-screen coordinates and are verified with a fresh capture.
- OpenAI is primary and Gemini is fallback; secrets remain server-side.
- The app must not claim perfect gameplay or hidden game-memory access.

---

### Task 1: Generic vision and provider contracts

**Files:**
- Modify: `backend/src/aiProviders.js`
- Modify: `backend/src/assistant.js`
- Modify: `backend/src/analysis.js`
- Test: `backend/src/*.test.js` existing provider/assistant tests

**Interfaces:**
- `normalizeImages(frameSet)` accepts `{images:[{data,mimeType,role,width,height}]}` or the legacy single-image shape and returns a normalized array.
- `buildAssistantPrompt(instruction, history)` returns a generic conversational prompt.
- `buildAutomationPrompt(goal, history)` returns a generic one-step action-planning prompt.
- `askWithOpenAI(images, instruction, history)` and `askWithGemini(images, instruction, history)` answer conversation turns.
- `decideWithOpenAI(images, goal, history)` and `decideWithGemini(images, goal, history)` return an action plan matching the action schema.

- [ ] Write tests proving football-only fields are absent from generic prompts and that conversation history is included.
- [ ] Add strict action schema: `type`, normalized coordinates, duration, waitMs, reason, confidence, verify, and stopReason.
- [ ] Update provider requests to include the full image set and generic prompts.
- [ ] Add OpenAI-first and Gemini-fallback action-decision functions.
- [ ] Run backend tests.

### Task 2: Frame-set ingestion and generic API

**Files:**
- Modify: `backend/src/server.js`
- Test: `backend/src/server.test.js` if present; otherwise add focused request-contract tests.

**Interfaces:**
- `POST /api/analyze-frame` accepts `{images:[...]}` and legacy `{image:...}`.
- `POST /api/ask` accepts `{instruction, messages}` and returns `{reply, provider}`.
- `POST /api/automation/decide` accepts `{goal, messages}` and returns `{action, provider}`.

- [ ] Store the latest frame set with timestamp and source dimensions.
- [ ] Reject missing/invalid images and stale automation requests.
- [ ] Bound conversation history to a small recent window.
- [ ] Add action-decision fallback behavior and structured errors.
- [ ] Run all backend tests.

### Task 3: Full-screen capture regions

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/MonitorService.kt`
- Test: Android build/CI compilation.

**Interfaces:**
- Frame upload produces `images` containing `full`, `top`, `middle`, and `bottom` overlapping views when the display is tall enough.
- All region coordinates refer to the original display dimensions.

- [ ] Convert the captured Image to one scaled bitmap without losing the original aspect ratio.
- [ ] Encode a full frame plus three overlapping crops at a bounded JPEG quality.
- [ ] Include source width/height and role metadata.
- [ ] Preserve the existing capture cadence and close/recycle all bitmaps.
- [ ] Compile the Android app.

### Task 4: Conversational overlay and voice reliability

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/AssistantOverlayService.kt`
- Modify: `app/src/main/java/com/gamevision/companion/MainActivity.kt`

**Interfaces:**
- Overlay keeps a bounded message history and sends it with each `/api/ask` request.
- Voice errors are mapped to readable states and recognizer is recreated after failure.

- [ ] Replace the single answer area with scrollable user/assistant message bubbles.
- [ ] Add clear, send, microphone, AUTO, and STOP controls.
- [ ] Persist recent conversation only for the active session.
- [ ] Send recent messages to the backend.
- [ ] Map `SpeechRecognizer` errors and recover cleanly.
- [ ] Add an Accessibility Settings button and status indicator.
- [ ] Compile the Android app.

### Task 5: Accessibility action executor

**Files:**
- Create: `app/src/main/java/com/gamevision/companion/GameVisionAccessibilityService.kt`
- Create: `app/src/main/res/xml/gamevision_accessibility_service.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- `GameVisionAccessibilityService.executeAction(action, callback)` supports TAP, LONG_PRESS, SWIPE, DRAG, and WAIT.
- Coordinates are normalized 0..1000 and mapped to the current display bounds.

- [ ] Add accessibility service metadata with gesture capability.
- [ ] Implement normalized coordinate mapping.
- [ ] Implement gesture dispatch callbacks.
- [ ] Return explicit success/failure to the automation controller.
- [ ] Add settings navigation from the main UI.
- [ ] Compile the Android app.

### Task 6: Autonomous control loop

**Files:**
- Create: `app/src/main/java/com/gamevision/companion/AutomationController.kt`
- Modify: `app/src/main/java/com/gamevision/companion/AssistantOverlayService.kt`
- Modify: `app/src/main/java/com/gamevision/companion/MonitorService.kt`

**Interfaces:**
- `AutomationController.start(goal)` begins one-action-at-a-time automation.
- `AutomationController.stop(reason)` immediately prevents future actions.
- Controller requests `/api/automation/decide`, executes the returned action through the accessibility service, waits, and requests a fresh decision after the next frame arrives.

- [ ] Require explicit AUTO activation and an accessibility-enabled check.
- [ ] Add a hard stop state that cancels pending requests and prevents queued actions.
- [ ] Send goal plus recent chat/action context.
- [ ] Execute at most one model action before verification.
- [ ] Stop on low confidence, repeated failures, stale frames, or model STOP.
- [ ] Show current action and verification state in the overlay.
- [ ] Compile the Android app.

### Task 7: End-to-end tests and CI

**Files:**
- Modify: `.github/workflows/android-build.yml` if required.
- Modify/add backend tests for frame-set, chat-history, and automation contracts.

- [ ] Run backend test suite.
- [ ] Run Android Gradle test/assembleDebug workflow.
- [ ] Confirm the workflow uploads `GameVision.apk`.
- [ ] Inspect the resulting GitHub Actions status before reporting completion.
