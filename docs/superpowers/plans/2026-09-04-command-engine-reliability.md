# Command Engine Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make natural-language device/game commands reliably reach vision control, make target taps use fresh visual coordinates, and preserve fast deterministic Android actions.

**Architecture:** Keep simple system/app commands local (Back, Home, app launch, directional scroll, typing). Route visual target interactions through the AI vision controller so coordinates come from the current frame. After each action, capture and verify a newer frame; use bounded recovery when the expected change is absent.

**Tech Stack:** Kotlin Android AccessibilityService/MediaProjection, Node.js backend, OpenRouter free routing, GitHub Actions.

**Spec:** GameVision natural-language command and game-assistance requirements approved in chat.

## Tasks

- [x] Add regression tests proving visual target commands do not get intercepted by the deterministic router.
- [x] Remove TAP_TARGET/DOUBLE_TAP_TARGET/LONG_PRESS_TARGET parsing from the fast router while retaining deterministic system/app actions.
- [x] Add mode-aware assistant/automation instructions for PLAY, ASSIST, WATCH, GUIDE, and MIXED behavior.
- [x] Preserve low-latency gesture defaults and direct OPEN_APP behavior.
- [ ] Run backend tests and Android unit tests/build in GitHub Actions and verify the resulting APK artifact.
- [ ] Verify Render receives the backend commit and health is live.
- [ ] Real-device smoke test: natural-language app launch, visual tap, hold, scroll, game assistance, and a multi-step goal.
