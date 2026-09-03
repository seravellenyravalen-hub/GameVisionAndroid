# GameVision General Game Agent Design

## Goal
Make GameVision game-agnostic: it should reason from visible screen content and a user's natural-language goal rather than being hard-coded for football or any other genre.

## Core behavior
- Accept typed or spoken natural-language conversation.
- Preserve recent conversation context.
- Capture the complete visible display and preserve small UI details through overlapping high-resolution regions.
- Treat the screen as the source of truth for visual/game state; never depend on football-specific fields.
- Support autonomous interaction when explicitly enabled by the user.
- Execute supported touch gestures locally through Android AccessibilityService, then recapture and verify before continuing.
- Stop on explicit user request, repeated uncertainty, stale capture, service failure, or an unsafe/unexpected state.

## Capability model
The backend exposes generic visual reasoning and action planning. Actions are capability-based: TAP, LONG_PRESS, SWIPE, DRAG, WAIT, and STOP. Coordinates are normalized to the full captured display so the Android client can map them to physical pixels regardless of device resolution.

## Data flow
`Display -> MediaProjection -> full frame + overlapping tiles -> /api/analyze-frame -> generic vision analysis`

`Display -> latest frame set -> /api/ask or /api/automation/decide -> action/answer -> Android -> AccessibilityService gesture -> Display -> verify`

## Providers
OpenAI remains the primary assistant/vision provider when available; Gemini remains a fallback. Provider prompts and schemas are generic and must not contain football-specific assumptions.

## UI
The floating assistant becomes a small conversational interface with message history, text input, voice input, AUTO/MANUAL state, and a prominent STOP action. Autonomous mode is opt-in and visibly indicated.

## Permissions and platform behavior
Screen capture continues to use MediaProjection and its foreground-service requirements. Autonomous gestures require an explicitly user-enabled AccessibilityService with gesture capability. The app must not silently enable accessibility or claim to have performed a gesture when Android reports failure.

## Verification
Every autonomous action is followed by a fresh capture before another action is selected. The model receives the goal, recent conversation/action context, and current screen set. The client records the planned action, dispatch result, and verification result in the local session log.

## Non-goals
- No football-only schema, scoring logic, or game-specific hard-coding.
- No hidden game-memory access.
- No unrestricted control of Android outside the supported visual/gesture capabilities.
- No claim of perfect gameplay or guaranteed success.
