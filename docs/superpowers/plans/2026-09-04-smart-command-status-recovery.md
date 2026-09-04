# Smart Command Status & Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add fast command execution, explicit lifecycle status, fresh-frame verification, bounded recovery, and truthful OpenRouter error handling to the existing Live Assistant.

**Architecture:** Keep deterministic routing in `FastCommandRouter`, orchestration in `AutomationController`, execution in `GameVisionAccessibilityService`, and UI/status rendering in `AssistantOverlayService`. Keep complex planning and OpenRouter credentials in the Node backend, with Render remaining the runtime secret boundary.

**Tech Stack:** Kotlin/Android AccessibilityService, MediaProjection-backed frame API, Node.js/Express, OpenRouter free route, Jest/Node test runner, Gradle 8.13/JDK 17.

**Spec:** `docs/superpowers/specs/2026-09-04-smart-command-status-recovery-design.md`

## Global Constraints
- Screen capture remains user-approved MediaProjection only.
- Automation starts only from an explicit user command.
- Every executed action requires fresh-frame verification before continuing/succeeding.
- Recovery is bounded and must terminate with a truthful failure reason.
- OpenRouter remains free-only and its key stays exclusively in Render environment variables.
- Failed AI reservations are refunded and authentication/session behavior is preserved.
- GitHub Actions must run backend tests, Android unit tests, and build `GameVision.apk`.

---

### Task 1: Make command routing cover the fast command surface

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/FastCommandRouter.kt`
- Test: `app/src/test/java/com/gamevision/companion/FastCommandRouterTest.kt`

**Interfaces:**
- Consumes: raw user command `String`.
- Produces: `AutomationAction?` for deterministic commands.

- [ ] **Step 1: Write failing tests** for back, home, scroll, tap-target, type, and wait parsing, plus a non-command returning null.
- [ ] **Step 2: Run `gradle --no-daemon testDebugUnitTest` and confirm the new assertions fail before implementation.
- [ ] **Step 3: Extend `FastCommandRouter.parse` with bounded scroll aliases and existing deterministic system actions without changing the `AutomationAction` contract.
- [ ] **Step 4: Run the focused Android unit test and then the full Android unit-test task.
- [ ] **Step 5: Commit with `feat: expand fast command routing`.

### Task 2: Add explicit lifecycle status and bounded recovery

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/AutomationController.kt`
- Test: `app/src/test/java/com/gamevision/companion/AutomationRecoveryTest.kt`

**Interfaces:**
- Consumes: `start(server, token, goal, previousMessages, listener)` and action callbacks.
- Produces: status strings using `THINKING`, `ACTING`, `VERIFYING`, `RECOVERING`, `SUCCESS`, and `FAILED` phases.

- [ ] **Step 1: Write tests for a successful lifecycle, a verification miss entering recovery, and recovery stopping after the fixed retry budget.
- [ ] **Step 2: Run the focused test and verify the new lifecycle assertions fail.
- [ ] **Step 3: Add a small recovery state/counter to `AutomationController`; emit `THINKING` before AI planning, `ACTING` before Android dispatch, `VERIFYING` after dispatch, `RECOVERING` before a bounded re-plan/retry, and `SUCCESS`/`FAILED` on terminal outcomes.
- [ ] **Step 4: Ensure deterministic commands still verify through the same fresh-frame gate and do not bypass authentication or accessibility requirements.
- [ ] **Step 5: Run all Android unit tests and commit with `feat: add command lifecycle recovery`.

### Task 3: Return truthful structured OpenRouter failures

**Files:**
- Modify: `backend/src/aiProviders.js`
- Modify: `backend/src/server.js`
- Test: `backend/src/aiProviders.test.js`
- Test: `backend/src/assistant.test.js`

**Interfaces:**
- Consumes: OpenRouter HTTP failures and missing/invalid server configuration.
- Produces: stable error codes such as `AI_NOT_CONFIGURED`, `FREE_AI_RATE_LIMITED`, and `FREE_AI_UPSTREAM_ERROR`, with safe actionable detail and no secrets.

- [ ] **Step 1: Add failing Node tests that assert provider errors retain status/category information while never returning the API key or raw authorization headers.
- [ ] **Step 2: Run `npm test` in `backend` and verify the new assertions fail.
- [ ] **Step 3: Introduce a small error normalizer in `aiProviders.js` that categorizes configuration, rate-limit/quota, timeout, upstream, and malformed-response failures.
- [ ] **Step 4: Update `/api/ask`, `/api/automation/decide`, and `/api/analyze-frame` to return the stable code/category and safe detail; preserve credit refunds for failed AI requests.
- [ ] **Step 5: Run `npm test` and commit with `fix: expose actionable free ai errors`.

### Task 4: Surface backend reasons in the Live Assistant

**Files:**
- Modify: `app/src/main/java/com/gamevision/companion/AssistantOverlayService.kt`
- Test: `app/src/test/java/com/gamevision/companion/AssistantStatusTest.kt`

**Interfaces:**
- Consumes: HTTP status, JSON `code`, and safe `error` text from backend/controller callbacks.
- Produces: user-visible assistant status and messages that distinguish sign-in, allowance, rate-limit, configuration, recovery, and connection failures.

- [ ] **Step 1: Write failing tests for mapping `FREE_AI_UPSTREAM_ERROR`, `FREE_AI_RATE_LIMITED`, `AI_NOT_CONFIGURED`, `401`, and `FREE_ALLOWANCE_EXHAUSTED` to distinct user-facing states.
- [ ] **Step 2: Run focused Android tests and verify they fail.
- [ ] **Step 3: Replace generic AI-unavailable branches with code-aware status rendering while keeping secret data out of the UI.
- [ ] **Step 4: Keep live voice restart behavior and sign-in flow unchanged unless the command request itself failed.
- [ ] **Step 5: Run Android unit tests and commit with `fix: surface assistant service status`.

### Task 5: CI/build artifact and deployment verification

**Files:**
- Modify: `.github/workflows/android-build.yml` only if needed for artifact/version metadata.
- Modify: `app/build.gradle.kts` only if a version bump is needed.
- Modify: `README.md` with the new release behavior and verification notes.

**Interfaces:**
- Consumes: the completed source changes on `main`.
- Produces: passing GitHub Actions run and `GameVision.apk` artifact.

- [ ] **Step 1: Run backend tests and Android unit tests in GitHub Actions.
- [ ] **Step 2: Run `assembleDebug` and upload `GameVision.apk` using the existing artifact workflow.
- [ ] **Step 3: Confirm the commit's workflow run is green and inspect the artifact metadata.
- [ ] **Step 4: Confirm the Render service is configured for the repository/branch and auto-deploy behavior; do not expose or rotate the OpenRouter secret.
- [ ] **Step 5: Wait for the backend deployment to become healthy and verify `/health` reports OpenRouter configured/free-only and authentication readiness without exposing secrets.
- [ ] **Step 6: Publish the final APK artifact link only after the CI artifact exists and the backend deployment is healthy.

## Final Verification Checklist
- Fast commands route locally where supported.
- Complex commands still use the AI planner.
- Every action waits for a fresh frame before continuing or reporting success.
- Verification misses enter bounded recovery and eventually fail safely.
- UI visibly distinguishes thinking, acting, verifying, recovering, success, and failure.
- OpenRouter failures expose safe reason codes instead of a single generic message.
- Sign-in/session and credit refund behavior remains intact.
- Passive monitoring does not initiate actions.
- Render retains the OpenRouter key server-side.
- GitHub Actions produces a verified `GameVision.apk` artifact.
