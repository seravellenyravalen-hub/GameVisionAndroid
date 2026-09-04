# Smart Command Status & Recovery Design

**Date:** 2026-09-04

## Goal
Make Live Assistant command execution fast for deterministic commands, resilient for complex commands, and explicit about execution state while keeping all actions user-command initiated.

## Requirements
- Deterministic local routing handles simple commands such as tap, type, back, home, scroll, and related system actions without an AI round trip when possible.
- Complex instructions use the existing OpenRouter free-only planner.
- Every action is followed by fresh-frame verification before the next AI decision or successful completion.
- If an expected change does not appear, recovery is bounded: retry/re-plan a limited number of times, then stop with a truthful reason.
- Assistant status reports a stable lifecycle: THINKING, ACTING, VERIFYING, RECOVERING, SUCCESS/FAILED.
- OpenRouter failures expose the actual actionable reason from the backend instead of collapsing all failures into a generic unavailable message.
- The OpenRouter key remains server-side in Render and is never placed in the Android client.
- Existing sign-in/session and per-account credit behavior remains intact; failed AI calls refund reserved credits.
- Passive monitoring never initiates automation. Automation starts only from an explicit user command.
- Android CI builds a fresh APK artifact from GitHub.

## Architecture
`AssistantOverlayService` owns presentation and user intent. `FastCommandRouter` performs deterministic classification. `AutomationController` owns the command lifecycle, fresh-frame gate, bounded recovery, and status events. `GameVisionAccessibilityService` executes only approved actions supplied by the controller. The backend remains the authority for complex action planning and OpenRouter access.

The controller treats a successful Android gesture as only the "acting" phase; it cannot declare success until `/api/frame-status` proves a newer frame or a server epoch change. Recovery never loops indefinitely: action failures and verification failures are counted independently and terminate after a small fixed retry budget.

## Backend behavior
The backend returns structured provider errors with stable codes and a short safe detail. The Android client maps these into visible status/messages. Secret values, authorization headers, and OpenRouter response bodies are never returned to clients.

## Verification
- Kotlin unit tests cover deterministic routing and status/recovery state transitions where practical.
- Node tests cover OpenRouter error classification and structured API error responses.
- GitHub Actions runs backend tests, Android unit tests, and `assembleDebug`.
- The resulting `GameVision.apk` artifact is inspected after CI completes.
