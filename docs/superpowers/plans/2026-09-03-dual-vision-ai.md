# GameVision Dual Vision AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make OpenAI the primary GameVision vision analyzer while using Gemini as an independent second opinion, with conservative consensus/fallback behavior.

**Architecture:** The backend will call the configured OpenAI and Gemini vision providers in parallel for each frame. Provider outputs are normalized into one internal shape, then a verification layer compares score/minute/teams and confidence; OpenAI remains the preferred result when providers agree, while disagreement is explicitly marked for review. If one provider fails, the other can still supply an analysis.

**Tech Stack:** Node.js, Express, native `fetch`, OpenAI Responses API, Gemini `generateContent` API, Node built-in test runner.

**Spec:** Existing GameVision dual-provider design approved in chat on 2026-09-03.

## Global Constraints

- API keys remain server-side Railway Variables only.
- OpenAI is the primary provider.
- Gemini remains an independent second opinion/fallback.
- Never claim 100% certainty or invent unreadable scoreboard values.
- Disagreement must lower verification status rather than silently selecting a conflicting value.
- Android `/api/analyze-frame` response shape remains backward compatible.

---

### Task 1: Add provider/verification tests

**Files:**
- Create: `backend/src/analysis.test.js`
- Modify: `backend/package.json`
- Modify: `.github/workflows/android-build.yml`

- [ ] Add tests for normalizing provider output, agreeing results, disagreement producing review/unverified, and one-provider fallback.
- [ ] Add a `test` script using Node's built-in test runner.
- [ ] Run the backend tests in CI before the Android build.

### Task 2: Implement normalized dual-provider analysis

**Files:**
- Create: `backend/src/analysis.js`
- Create: `backend/src/aiProviders.js`
- Modify: `backend/src/server.js`

- [ ] Implement shared result schema/normalization and conservative consensus logic.
- [ ] Implement OpenAI Responses API image analysis using `OPENAI_API_KEY` and configurable `OPENAI_MODEL`, defaulting to `gpt-5.6-sol`.
- [ ] Keep Gemini image analysis using `GEMINI_API_KEY`, defaulting to `gemini-3.8-flash`.
- [ ] Call both providers in parallel when both keys are present.
- [ ] Use either provider when the other is unavailable.
- [ ] Return provider/consensus metadata without breaking existing Android fields.
- [ ] Keep request timeouts and safe upstream error handling.

### Task 3: Verify deployment and Android compatibility

**Files:**
- Modify only if verification reveals a compatibility issue.

- [ ] Confirm GitHub Actions backend tests pass.
- [ ] Confirm Android build remains successful.
- [ ] Confirm Railway receives the new deployment.
- [ ] Confirm `/health` reports both provider configuration states without exposing secrets.
- [ ] Confirm `/api/analyze-frame` produces a conservative result when one or both providers are available.
