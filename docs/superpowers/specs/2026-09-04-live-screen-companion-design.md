# GameVision Live Screen Companion Design

## Goal

Make GameVision behave like a live smart screen companion: while monitoring is enabled, it continuously captures the complete Android display, maintains current visual understanding, detects meaningful screen changes, and is ready to execute explicit user commands with reliable post-action verification.

## Approved behavior

- Live Screening is enabled by monitoring, not by voice input.
- The AI continuously maintains knowledge of the full visible screen/page.
- The AI does not autonomously tap, swipe, type, navigate, or otherwise control the device during passive observation.
- User commands trigger an action planner that uses the newest screen state and explicit coordinate/action grounding.
- After an action, GameVision waits for a newer screen frame and verifies the expected result before considering the task successful.
- The live HUD should expose a fast, smart-tool status: monitoring state, current screen understanding freshness, action state, and verification result.

## Architecture

### 1. Android capture layer

`MonitorService` owns the MediaProjection stream. It captures the entire display at the display's native dimensions, continuously drains `ImageReader`, and publishes transient frames to the backend. Uploading is decoupled from AI analysis so capture remains responsive even when model inference is slow.

The capture path must preserve the complete screen. Any resized representation sent to vision must preserve the entire aspect ratio; crops may only be supplemental evidence and must never replace the full-screen frame.

### 2. Live frame/session state

The backend maintains a bounded, transient live session per authenticated user. The state contains the newest frame, sequence, capture timestamp, server epoch, dimensions, a lightweight change fingerprint, and the latest AI screen understanding. Screenshots are not persisted to the database.

A lightweight change detector prevents identical frames from repeatedly consuming model requests. Meaningful changes mark the live screen state dirty and schedule fresh vision analysis with rate limiting/debouncing appropriate for the free OpenRouter model.

### 3. Full-screen AI understanding

Vision analysis receives the complete current frame and is instructed to inventory the entire visible screen: current app/page/state, visible text, important regions, controls, game/UI elements, selected/focused state, overlays, and actionable targets. Coordinates are returned in the original full-frame coordinate system or as normalized coordinates with explicit frame dimensions.

For ordinary apps, available Accessibility information can supplement visual understanding. For games and canvas-rendered interfaces, the screenshot remains the primary source of truth.

### 4. Command/action pipeline

Commands use the newest frame and current AI screen state. The planner returns a structured action with action type, target description, coordinates when required, confidence, and expected visual outcome. Coordinates are transformed deterministically from captured-frame space into actual display space using the known capture/display dimensions.

Accessibility executes the action. GameVision then waits for a newer frame/epoch, analyzes the resulting screen state, and verifies that the expected change occurred. Failed verification triggers bounded re-planning/retry using the newly observed screen rather than repeating stale coordinates. Repeated genuine failures cause a safe stop and visible explanation.

### 5. Live HUD

The HUD presents concise state rather than raw logs. It should show states such as `LIVE`, `ANALYZING`, `READY`, `ACTING`, `VERIFYING`, `SUCCESS`, and `OFFLINE`, plus a compact description of the current screen and frame freshness. It must not block or intercept touches on the underlying app.

### 6. Responsiveness

Capture, upload, analysis, and command execution are separated so a slow model response cannot stall screen capture. Duplicate frames are skipped for AI inference. Command execution always prioritizes the newest frame over waiting for a background analysis cycle. Timeouts, transient network errors, backend restarts, and stale frames are handled explicitly.

### 7. 24/7 operational model

When monitoring remains enabled, GameVision keeps the foreground monitoring service and live session active and attempts recovery from transient failures. Android OS restrictions, revoked MediaProjection consent, revoked Accessibility permission, forced service termination, device shutdown, or battery/OS policy can interrupt monitoring; the app must detect and report those conditions rather than falsely claiming continuous operation.

## Verification requirements

Before release of the feature:

1. Backend unit tests cover frame freshness, duplicate/change detection, live-state updates, restart epochs, action verification, and stale-frame rejection.
2. Android unit tests cover coordinate transformation, live-state parsing, frame freshness, and command payload grounding.
3. GitHub Actions must pass backend tests, Android tests, and debug APK build.
4. Render deployment must be confirmed on the current `main` commit and `/health`/service state must be checked through the connected Render tooling.
5. The final APK artifact must be produced successfully.
6. Repository status/commit and deployment state must be checked before claiming completion.

## Constraints

- Continue modifying `seravellenyravalen-hub/GameVisionAndroid`; do not start a new project.
- Use the existing Render `gamevision-api` service in Seravelle's workspace.
- Keep OpenRouter free-model usage bounded and efficient.
- Never expose the user's OpenRouter secret.
- Do not store screenshots permanently.
- Do not claim Android can guarantee uninterrupted 24/7 execution when the OS or permissions can stop the service.
- Automation remains command-driven; passive Live Screening never performs autonomous actions.
