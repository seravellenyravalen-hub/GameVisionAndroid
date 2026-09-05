# Multi-Provider Live Vision Design

## Goal
Make GameVision genuinely live while preventing AI allowance from being consumed by every frame, retry, or verification cycle.

## Design
GameVision will keep a continuous local MediaProjection frame buffer. Frames are captured continuously and the newest valid external-app frame is always available for commands and verification. Network uploads are adaptive and never block local frame capture.

AI generation will use a **provider pool**, not three simultaneous generations for every request. Gemini, OpenAI, and OpenRouter are all configured as independent providers and are selected/rotated per AI operation. If the selected provider is unavailable or rate-limited, the request fails over to another configured provider. Running all three for every command would consume three upstream quotas for one decision and would make the free allowance problem worse.

The app-level allowance will count **user AI reasoning sessions**, not every automation step. A single authorized automation session reserves one allowance unit; its observe → decide → act → verify loop may make multiple provider calls without decrementing the user's allowance for each step. Failed upstream calls are refundable. Fast local commands do not consume AI allowance.

## Provider roles
- Gemini: primary free vision/fast reasoning candidate when configured.
- OpenRouter: free-model pool and fallback when configured.
- OpenAI: optional configured provider/fallback; only used when its key/quota is actually usable.
- Provider health, cooldowns, and rotation prevent repeatedly hammering a failing provider.
- No provider key is exposed to Android; all keys remain server-side Render environment variables.

## Live vision rules
- Capture target: approximately 12 FPS locally using `acquireLatestImage()`.
- The frame-processing executor must never wait on network I/O.
- At most one upload is active; a newer frame replaces stale pending work.
- Adaptive server upload target: changed frames promptly with a bounded cadence and a heartbeat for unchanged screens.
- GameVision's own foreground Activity is not treated as game vision, preventing recursive self-capture.
- After an action, verification waits for a genuinely newer server frame.

## Credit protection
- Do not call cloud AI for simple local commands when the fast router can execute them.
- Do not analyze every captured frame.
- Do not charge an allowance unit for frame upload, frame status polling, or local action execution.
- Do not charge another unit for every recovery/verification step inside the same user automation session.
- Do not run all three providers concurrently except for an explicitly bounded future consensus feature; normal operation is one provider at a time with failover.

## Compatibility and safety
Authentication remains unchanged and must continue to protect all AI/frame endpoints. MediaProjection remains user-consented and Android-controlled. No root, stealth capture, or security bypass is introduced.
