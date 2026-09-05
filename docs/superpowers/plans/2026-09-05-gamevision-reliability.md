# GameVision Reliability Repair Implementation Plan

## Goal
Make the native Android assistant reliable when the main app is minimized: navigation must respond, screen vision must represent the underlying user app/game rather than GameVision's own overlay, live voice must continue after the panel is minimized, and explicit commands must result in verified Android actions rather than only an AI response claiming that an action happened.

## Root causes found
- `AssistantOverlayService` uses an application overlay and its current windows are not marked secure, so MediaProjection can include GameVision UI in captured display content.
- Voice recognition currently writes recognized text into the panel's `EditText` and calls `sendCurrent()`. When the panel is minimized, `input` is null, so recognized speech is discarded instead of becoming a command.
- `AssistantOverlayService` is declared only as a `specialUse` foreground service. Android requires a microphone foreground-service type and permission for continued microphone access from a backgrounded app.
- The automation path can dispatch gestures through Accessibility, but it needs stronger execution diagnostics and coordinate validation so an AI plan cannot be presented as successful without an Android completion callback and fresh-frame verification.
- MainActivity already has tab listeners, so navigation needs device-facing hardening (z-order/clickability/state refresh) rather than another superficial listener-only patch.

## Files to modify
- `app/src/main/AndroidManifest.xml`: declare microphone FGS permission and combine `specialUse|microphone` for the assistant service.
- `app/src/main/java/com/gamevision/companion/AssistantOverlayService.kt`: secure overlay windows, keep voice independent of the panel, add minimize/restore behavior, start microphone FGS type when voice is enabled, and surface execution state even when minimized.
- `app/src/main/java/com/gamevision/companion/GameVisionAccessibilityService.kt`: harden gesture dispatch diagnostics, validate normalized coordinates, and keep Android success/failure authoritative.
- `app/src/main/java/com/gamevision/companion/MonitorService.kt`: keep the capture loop independent of HUD visibility and make the capture contract explicit for secure GameVision overlay windows.
- `app/src/main/java/com/gamevision/companion/MainActivity.kt`: harden bottom navigation ordering/click targets and refresh UI state on resume.
- `app/src/test/java/com/gamevision/companion/FastCommandRouterTest.kt` and additional focused Android tests: cover natural command classification and execution-path routing.
- `backend/src/assistant.test.js`: add regression tests for action normalization and non-invented success semantics where practical.

## Test-first sequence
1. Add failing unit tests for background voice routing semantics: recognized text must route directly to the same command dispatcher whether the panel is open or minimized.
2. Add failing tests for command classification of natural phrases such as “please tap the enemy”, “can you open YouTube”, and “I want you to press that button”, while keeping ordinary questions as chat.
3. Implement the minimal command/voice routing changes.
4. Add tests for action result semantics and coordinate normalization.
5. Implement Accessibility execution hardening and status reporting.
6. Add microphone foreground-service declarations and lifecycle handling.
7. Make overlay windows secure so their contents are excluded from screenshots/media projection while the underlying display remains capturable.
8. Harden navigation z-order/clickability and add deterministic page-state refresh.
9. Run backend tests and Android unit tests.
10. Build the debug APK with the existing GitHub Actions workflow.
11. Inspect the resulting workflow and artifact status before claiming the build is ready.

## Acceptance criteria
- Tapping every bottom tab changes the visible page reliably.
- With Monitor active and GameVision minimized, fresh frames continue arriving without reopening the panel.
- Captured vision does not contain GameVision's own floating UI; GameVision remains visible to the user as an overlay.
- Enabling live voice while GameVision is open continues listening after the panel is minimized.
- Recognized speech is dispatched as a command even when the panel is closed/minimized.
- “Open <app>” launches the installed app directly without depending on an icon being visible.
- Visual commands such as “tap the enemy” use the AI vision path, execute through Accessibility, and report the actual Android callback result.
- A command is not reported as successful merely because the AI requested it; success requires Android execution success and the verification frame flow.
- Failed gestures produce a useful recovery/error status instead of a false success.
- Sign-in/authentication behavior remains intact.
- Backend tests, Android unit tests, and APK build all pass in fresh CI.

## Platform boundary
The repair will stay within Android's authorized Accessibility, MediaProjection, overlay, and foreground-service APIs. It will not bypass Android security, root requirements, app sandboxing, or user permission prompts. Background microphone operation will remain subject to Android's foreground-service and while-in-use permission rules.
