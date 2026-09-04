# GameVision Smart Command Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify the current approved GameVision Android release with authentication continuity, polished home experience, live screen monitoring, reliable command gestures, AI fallback, fresh-frame verification, bounded recovery, clear command status, and a healthy production backend.

**Architecture:** Keep the existing native Android companion and Render-hosted Node backend architecture. Simple commands route locally through the Accessibility command controller; complex commands use the server-side free-only OpenRouter planner. Every action is followed by fresh-frame verification, with bounded recovery and explicit status transitions.

**Tech Stack:** Native Android, Kotlin/Gradle, Android MediaProjection, AccessibilityService, Node.js backend, OpenRouter, GitHub Actions, Render.

**Spec:** Current approved GameVision release requirements from the project README and approved Smart Command Status & Recovery design.

## Global Constraints

- Production backend URL is `https://gamevision-api.onrender.com`.
- `OPENROUTER_API_KEY` remains server-side in Render and is never stored in the APK.
- Passive monitoring never starts automation; actions require an explicit user command.
- MediaProjection is used only after Android screen-capture approval.
- Accessibility actions are limited to explicit assistant commands and bounded recovery.
- Free-only OpenRouter routing is preserved.
- Sign-in/authentication must remain functional.
- The Android build must pass backend tests, Android unit tests, and produce `GameVision.apk`.
- Do not use Replit.

---

### Task 1: Baseline and release verification

**Files:**
- Inspect: `README.md`
- Inspect: `.github/workflows/android-build.yml`
- Inspect: Android and backend source files already implementing the approved release

**Interfaces:**
- Consumes: current `main` branch at the approved release commit.
- Produces: a verified inventory of existing implementation and CI entry points.

- [ ] **Step 1: Verify the current release requirements against repository documentation**

Confirm that the repository documents fast local commands, AI fallback, fresh-frame verification, bounded recovery, explicit status states, server-side OpenRouter credentials, and explicit-command safety boundaries.

- [ ] **Step 2: Inspect the existing CI workflow**

Confirm `.github/workflows/android-build.yml` runs backend tests, Android unit tests, `assembleDebug`, renames the APK to `GameVision.apk`, and uploads it as an artifact.

- [ ] **Step 3: Verify the production backend deployment**

Inspect the current Render deployment for commit `9c596f641846a614038bac1d8abe1b554e2ca026`. Do not call the release complete until its deployment reaches `live`.

---

### Task 2: Build the Android release through GitHub Actions

**Files:**
- Modify only source/workflow files if verification exposes a concrete defect.
- Artifact: `app/build/outputs/apk/debug/GameVision.apk`

**Interfaces:**
- Consumes: `main` branch and `.github/workflows/android-build.yml`.
- Produces: passing backend tests, passing Android unit tests, and a downloadable `GameVision.apk` artifact.

- [ ] **Step 1: Trigger the existing Android workflow**

Use GitHub Actions workflow dispatch or the repository's push-triggered workflow without changing the release design.

- [ ] **Step 2: Wait for the workflow to finish**

Verify the run conclusion is successful rather than assuming success from workflow configuration.

- [ ] **Step 3: Verify the APK artifact**

Confirm an artifact named `GameVision` exists and contains `GameVision.apk`.

- [ ] **Step 4: Download the verified artifact when supported**

Use the GitHub Actions artifact endpoint to obtain the exact built APK ZIP/artifact reference if the connector exposes it.

---

### Task 3: Fix only concrete release blockers

**Files:**
- Modify: exact source file identified by failing test/build/deployment output
- Test: the failing existing test or build step

**Interfaces:**
- Consumes: concrete failure output from Task 1 or Task 2.
- Produces: a minimal, tested fix on `main`.

- [ ] **Step 1: Reproduce or inspect the exact failure**

Use the failing workflow/deployment output as the source of truth. Do not redesign unrelated components.

- [ ] **Step 2: Add or update a focused regression test before changing behavior**

The test must encode the observed failure and fail before the fix.

- [ ] **Step 3: Apply the smallest compatible fix**

Preserve existing interfaces and safety boundaries.

- [ ] **Step 4: Re-run the focused test**

Require a passing result before moving to the full build.

- [ ] **Step 5: Commit the fix**

Use a focused commit message describing the concrete release blocker.

---

### Task 4: End-to-end release verification

**Files:**
- No source changes unless a concrete blocker is discovered.

**Interfaces:**
- Consumes: successful Android workflow artifact and successful Render deployment.
- Produces: evidence-backed release status.

- [ ] **Step 1: Verify Android CI conclusion**

Require successful backend tests, Android unit tests, APK assembly, and artifact upload.

- [ ] **Step 2: Verify Render deployment state**

Require the deployment for the final `main` commit to report `live`.

- [ ] **Step 3: Verify the final commit alignment**

Confirm GitHub `main`, the successful Android build commit, and the live Render deployment all reference the same final commit.

- [ ] **Step 4: Report only verified results**

State exactly what passed, what artifact was produced, and the live backend status. Do not claim device-level behavior has been verified unless an actual Android device test was performed.
