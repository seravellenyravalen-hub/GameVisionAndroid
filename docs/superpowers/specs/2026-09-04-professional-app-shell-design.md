# GameVision Professional App Shell Design

## Goal
Transform the current developer/setup-heavy Android main screen into a polished, product-style GameVision application shell while preserving the existing monitoring, AI assistant, screen capture, accessibility automation, and free OpenRouter backend behavior.

## Product direction
GameVision should present itself as a real visual game assistant, not as a developer console. Backend URLs, Railway details, raw provider names, and implementation diagnostics must not appear in the normal user-facing UI.

The visual language remains the existing dark GameVision aesthetic with lime accents, but the information hierarchy becomes product-oriented: clear status, large primary actions, dedicated areas for Assistant, Monitor, Activity, and Settings, and concise copy.

## Navigation
Use a single-activity Android shell with product sections represented as separate content states/screens:
- Home: overview, AI readiness, quick actions, current monitoring state.
- Assistant: entry point to the floating G/V assistant and voice/text interaction.
- Monitor: screen capture and monitoring controls/status.
- Activity: recent GameVision actions/events and execution outcomes.
- Settings: permissions, assistant preferences, automation preferences, appearance, and about information.

The existing services remain the execution layer; navigation must not duplicate or replace those services.

## Home
The home screen should contain:
- GameVision branding and a compact online/ready indicator.
- AI Engine card showing a human-readable state such as `READY` / `RECONNECTING` rather than `OPENROUTER`.
- Monitoring summary card.
- Quick actions for Monitor, Assistant, and Auto Control.
- A small activity/recent-actions preview.
- No editable backend/server URL.
- No long privacy/developer explanations on the primary screen.

## Monitor
Provide a focused monitoring screen with:
- Live/stopped capture status.
- AI readiness.
- Auto Control readiness.
- Start and Stop controls.
- HUD toggle.
- Link to required Android permissions when unavailable.

The start action remains disabled while monitoring is already active.

## Assistant
Provide a product-style assistant entry point while keeping the existing floating overlay. The UI should make it obvious that the user can type or speak and can switch to AUTO when they want interaction with the current visible game.

## Activity
Show a lightweight in-memory session history of recent assistant/automation events, including command, state, and outcome where available. Do not expose API URLs or secret/provider details.

## Settings
Group settings into:
- Permissions & Access: screen capture, overlay, accessibility, microphone.
- Assistant: voice input/output and assistant behavior.
- Automation: Auto Control and gesture behavior.
- Appearance: theme/HUD/floating assistant preferences.
- About: app version and product information.

Settings must launch the existing Android system settings flows rather than attempting to circumvent Android permissions.

## Privacy and technical information
Technical information remains available only where useful for troubleshooting, but the primary product UI must not expose the Railway backend URL or require the user to configure it. The app continues to use its internal configured backend endpoint.

## Visual design
- Dark near-black background.
- Lime primary accent.
- Rounded cards with subtle borders.
- Consistent 16–20dp spacing.
- Strong typography hierarchy.
- Material-style icons/vector drawables rather than emoji or text pretending to be icons.
- Bottom navigation with five destinations.
- Buttons should be action-oriented and visually prioritized instead of presenting every option as a large outlined button.
- Product copy should be concise and confident.

## Compatibility and behavior constraints
- Preserve MediaProjection screen sharing approval.
- Preserve foreground monitoring service behavior.
- Preserve floating G/V assistant.
- Preserve SpeechRecognizer/TTS behavior.
- Preserve AccessibilityService gesture automation.
- Preserve free-only OpenRouter backend behavior.
- Never claim an action succeeded unless the underlying Android operation reports success.
- Android security/permission boundaries remain intact.

## Testing / acceptance
The resulting debug build must compile successfully. The home screen must contain no visible backend URL. Navigation must switch between all five sections without crashing. Existing start/stop monitoring behavior must remain functional, and the monitor start control must remain disabled while monitoring is active. Existing assistant and permission entry points must remain reachable.
