# GameVision Android Companion Design

## Goal
Provide an authorized native Android companion that captures the user's explicitly approved screen, sends visible frames to the existing GameVision analysis API, and displays analysis in a floating HUD above the game.

## Architecture
The existing AppDeploy React dashboard remains the control/history surface. A separate native Kotlin Android application owns OS-level MediaProjection capture, a mediaProjection foreground service, Android Text-to-Speech, and a TYPE_APPLICATION_OVERLAY HUD. The companion never reads hidden game/server state and does not automate game input.

## Flow
1. User opens GameVision Companion.
2. User configures the GameVision server URL.
3. User grants Android overlay permission if desired.
4. User taps Start monitoring and approves the system screen-capture dialog.
5. Foreground service starts with mediaProjection type.
6. MediaProjection feeds ImageReader frames.
7. Frames are resized/JPEG encoded and POSTed to `/api/analyze-frame`.
8. Analysis confidence/verification/score/notes update the floating HUD and optional Android TTS.
9. User stops monitoring; projection, overlay, TTS and foreground service are released.

## Reliability
- Require explicit screen-share consent for every capture session.
- Handle projection termination and service cleanup.
- Limit uploads to about one frame every 1.5 seconds.
- Use connect/read timeouts and surface upload failures in the HUD.
- Keep the overlay non-focusable and non-touchable so it does not interfere with the game.
- Do not silently restart a revoked projection.

## Security and privacy
Only user-visible frames are analyzed. No credential harvesting, hidden telemetry extraction, game-memory access, purchase bypass, or automated gameplay is included. The app shows an ongoing foreground notification while capture is active.
