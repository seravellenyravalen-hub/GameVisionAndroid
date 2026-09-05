import express from "express";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";
import { buildAssistantPrompt, isScreenDependentInstruction, normalizeAction, normalizeAssistantReply, normalizeHistory } from "./assistant.js";
import { analyzeWithProviders, askWithProviders, decideWithProviders, getProviderStatus } from "./aiProviders.js";
import { authMiddleware, consumeCredit, createAccount, getUserForToken, loginAccount, logoutToken, reserveAutomationCredit, ensureAuthSchema, refundCredit } from "./auth.js";
import { FrameStore } from "./frameStore.js";

const app = express();
const PORT = process.env.PORT || 3000;
const MAX_FRAME_AGE_MS = 15000;
const frameStore = new FrameStore({ maxAgeMs: MAX_FRAME_AGE_MS });

app.use(express.json({ limit: "12mb" }));
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept, User-Agent, Authorization");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

function normalizeImages(body) {
  const candidates = Array.isArray(body?.images) ? body.images : body?.image ? [body.image] : [];
  return candidates.filter((image) => image?.data && ["image/jpeg", "image/png", "image/webp"].includes(image.mimeType)).slice(0, 5).map((image) => ({ data: String(image.data), mimeType: image.mimeType, role: String(image.role || "region"), width: Number(image.width) || 0, height: Number(image.height) || 0, x: Number(image.x) || 0, y: Number(image.y) || 0 }));
}
function frameStatus(userId = null) { return frameStore.status(userId); }
function providerFailure(res, error, fallbackCode = "FREE_AI_UPSTREAM_ERROR", extra = {}) {
  const code = String(error?.code || fallbackCode); const status = Number(error?.status) || 502; const retryable = Boolean(error?.retryable ?? (status >= 500 || status === 429));
  const safeError = String(error?.message || "Free AI provider request failed").replace(/[\r\n]+/g, " ").slice(0, 240);
  return res.status(status >= 400 && status < 600 ? status : 502).json({ error: safeError, code, retryable, freeOnly: true, ...extra });
}

function requireFreshFrame(res, userId, minSequence = 0, minEpoch = null, allowToolOnly = false) {
  const frame = frameStore.get(userId); const status = frameStatus(userId);
  if (!frame) {
    if (allowToolOnly) return true;
    res.status(409).json({ error: "Waiting for the first screen capture", code: "FRAME_NEEDED", frame: status }); return false;
  }
  const epochChanged = minEpoch != null && String(minEpoch) !== frame.serverEpoch;
  if (!epochChanged && frame.sequence <= minSequence) {
    if (allowToolOnly) return true;
    res.status(409).json({ error: "Waiting for a newer screen capture", code: "FRESH_FRAME_NEEDED", frame: status, minSequence, minEpoch }); return false;
  }
  if (!frameStore.isFresh(userId, minSequence, minEpoch)) {
    if (allowToolOnly) return true;
    res.status(409).json({ error: "Waiting for a fresh screen capture", code: "STALE_FRAME", frame: status, minSequence, minEpoch }); return false;
  }
  return true;
}

async function authReady() { if (!process.env.DATABASE_URL) return false; try { await ensureAuthSchema(); return true; } catch (error) { console.error("Auth database is not ready:", error?.message || error); return false; } }

app.get("/health", async (req, res) => {
  const ai = getProviderStatus(); const databaseConfigured = Boolean(process.env.DATABASE_URL); const databaseReady = databaseConfigured ? await authReady() : false;
  res.status(200).json({ status: "healthy", service: "gamevision-api", aiConfigured: ai.configured, aiAvailable: ai.available.length > 0, freeOnly: true, provider: ai.lastProvider, providers: ai.providers, availableProviders: ai.available, models: ai.models, authConfigured: databaseConfigured, authReady: databaseReady, frame: frameStatus(), timestamp: new Date().toISOString() });
});
app.get("/", (req, res) => { const ai = getProviderStatus(); res.json({ service: "GameVision API", status: "online", aiConfigured: ai.configured, aiAvailable: ai.available.length > 0, freeOnly: true, providers: ai.providers, availableProviders: ai.available, models: ai.models, authConfigured: Boolean(process.env.DATABASE_URL), frame: frameStatus() }); });

app.post("/api/auth/signup", async (req, res) => { try { const user = await createAccount(req.body?.email, req.body?.password); const session = await loginAccount(user.email, req.body?.password); res.status(201).json({ token: session.token, user: session.user }); } catch (error) { const status = ["INVALID_EMAIL", "ACCOUNT_EXISTS"].includes(error?.code) ? 400 : 503; res.status(status).json({ error: error?.message || "Account creation failed", code: error?.code || "AUTH_UNAVAILABLE" }); } });
app.post("/api/auth/login", async (req, res) => { try { const session = await loginAccount(req.body?.email, req.body?.password); res.json({ token: session.token, user: session.user }); } catch (error) { const status = error?.code === "INVALID_CREDENTIALS" ? 401 : 503; res.status(status).json({ error: error?.message || "Sign in failed", code: error?.code || "AUTH_UNAVAILABLE" }); } });
app.get("/api/auth/me", async (req, res) => { try { const token = String(req.headers.authorization || "").startsWith("Bearer ") ? String(req.headers.authorization).slice(7).trim() : ""; const user = await getUserForToken(token); if (!user) return res.status(401).json({ error: "Sign in required", code: "AUTH_REQUIRED" }); res.json({ user }); } catch { res.status(503).json({ error: "Account service unavailable", code: "AUTH_UNAVAILABLE" }); } });
app.post("/api/auth/logout", async (req, res) => { try { const token = String(req.headers.authorization || "").startsWith("Bearer ") ? String(req.headers.authorization).slice(7).trim() : ""; await logoutToken(token); res.json({ ok: true }); } catch { res.status(503).json({ error: "Account service unavailable", code: "AUTH_UNAVAILABLE" }); } });
const requireAuth = authMiddleware();
app.get("/api/frame-status", requireAuth, (req, res) => res.json({ ...frameStatus(req.authUser.id), user: req.authUser }));
app.post("/api/frame", requireAuth, (req, res) => { const images = normalizeImages(req.body); if (!images.length) return res.status(400).json({ error: "Image payload required", code: "IMAGE_REQUIRED" }); const frame = frameStore.put(req.authUser.id, images); res.json({ ok: true, frame: { sequence: frame.sequence, capturedAt: frame.capturedAt, ageMs: 0, fresh: true, serverEpoch: frame.serverEpoch, fingerprint: frame.fingerprint } }); });

app.post("/api/analyze-frame", requireAuth, async (req, res) => {
  try {
    const images = normalizeImages(req.body); if (!images.length) return res.status(400).json({ error: "Image payload required", code: "IMAGE_REQUIRED" }); const frame = frameStore.put(req.authUser.id, images);
    if (!getProviderStatus().configured) return res.status(503).json({ error: "No AI provider is configured on the server. Add GEMINI_API_KEY, OPENAI_API_KEY, or OPENROUTER_API_KEY in Render.", code: "AI_NOT_CONFIGURED", freeOnly: true, frame: frameStatus(req.authUser.id) });
    try { const { result: raw, provider } = await analyzeWithProviders(images); const analysis = mergeProviderResults(normalizeProviderResult(raw, provider), null); return res.json({ analysis, providers: { active: provider, available: getProviderStatus().available }, activeProvider: provider, frame: frameStatus(req.authUser.id), freeOnly: true, usage: { creditsRemaining: req.authUser.creditsRemaining } }); }
    catch (error) { return providerFailure(res, error); }
  } catch (error) { console.error("GameVision frame analysis error:", error?.message || error); return res.status(502).json({ error: "Unable to analyze frame right now. Please retry.", code: "AI_ANALYSIS_FAILED", retryable: true, freeOnly: true }); }
});

app.post("/api/ask", requireAuth, async (req, res) => {
  let reservedCredit = false;
  try {
    const instruction = String(req.body?.instruction || "").trim(); if (!instruction) return res.status(400).json({ error: "Instruction required", code: "INSTRUCTION_REQUIRED" });
    if (!getProviderStatus().configured) return res.status(503).json({ error: "No AI provider is configured on the server. Add GEMINI_API_KEY, OPENAI_API_KEY, or OPENROUTER_API_KEY in Render.", code: "AI_NOT_CONFIGURED", freeOnly: true });
    const usage = await consumeCredit(req.authUser.id); if (!usage.allowed) return res.status(429).json({ error: "Your free GameVision allowance has been used. It will reset automatically.", code: "FREE_ALLOWANCE_EXHAUSTED", resetAt: usage.user.resetAt, creditsRemaining: 0 }); reservedCredit = true;
    const history = normalizeHistory(req.body?.messages); const visualRequest = isScreenDependentInstruction(instruction); const frame = frameStore.get(req.authUser.id); const hasFreshFrame = Boolean(frame && frameStore.isFresh(req.authUser.id));
    if (visualRequest && !hasFreshFrame) { const refunded = await refundCredit(req.authUser.id); reservedCredit = false; return res.status(409).json({ error: "Waiting for a fresh screen capture", code: "FRESH_FRAME_NEEDED", frame: frameStatus(req.authUser.id), usage: { creditsRemaining: refunded?.creditsRemaining ?? usage.user.creditsRemaining + 1 } }); }
    try { const images = hasFreshFrame ? frame.images : []; const { result: raw, provider } = await askWithProviders(images, instruction, history, visualRequest && hasFreshFrame); return res.json({ reply: normalizeAssistantReply(raw), provider, visionUsed: images.length > 0, visionFresh: hasFreshFrame, frame: frameStatus(req.authUser.id), freeOnly: true, providers: getProviderStatus(), usage: { creditsRemaining: usage.user.creditsRemaining }, instruction: buildAssistantPrompt(instruction, history, images.length > 0, visualRequest && hasFreshFrame).split("CURRENT USER MESSAGE:\n")[1] || instruction }); }
    catch (error) { const refunded = await refundCredit(req.authUser.id); reservedCredit = false; return providerFailure(res, error, "FREE_AI_UPSTREAM_ERROR", { usage: { creditsRemaining: refunded?.creditsRemaining ?? usage.user.creditsRemaining } }); }
  } catch (error) { console.error("GameVision assistant error:", error?.message || error); if (reservedCredit) { try { await refundCredit(req.authUser.id); } catch (refundError) { console.error("Unable to refund failed assistant credit:", refundError?.message || refundError); } } return res.status(502).json({ error: "Unable to answer right now. Please retry.", code: "ASSISTANT_FAILED", retryable: true, freeOnly: true }); }
});

app.post("/api/automation/decide", requireAuth, async (req, res) => {
  let reservation = null;
  try {
    const goal = String(req.body?.goal || "").trim(); if (!goal) return res.status(400).json({ error: "Automation goal required", code: "GOAL_REQUIRED" });
    const minSequence = Number(req.body?.minFrameSequence) || 0; const minEpoch = req.body?.minFrameEpoch == null ? null : String(req.body.minFrameEpoch); const allowToolOnly = req.body?.allowToolOnly === true;
    if (!requireFreshFrame(res, req.authUser.id, minSequence, minEpoch, allowToolOnly)) return;
    if (!getProviderStatus().configured) return res.status(503).json({ error: "No AI provider is configured on the server. Add GEMINI_API_KEY, OPENAI_API_KEY, or OPENROUTER_API_KEY in Render.", code: "AI_NOT_CONFIGURED", freeOnly: true });
    reservation = await reserveAutomationCredit(req.authUser.id, req.body?.aiSessionId || null); if (!reservation.allowed) return res.status(429).json({ error: "Your free GameVision allowance has been used. It will reset automatically.", code: "FREE_ALLOWANCE_EXHAUSTED", resetAt: reservation.user.resetAt, creditsRemaining: 0 });
    const history = normalizeHistory(req.body?.messages); const frame = frameStore.get(req.authUser.id); const images = frame?.images || [];
    try { const { result: raw, provider } = await decideWithProviders(images, goal, history); return res.json({ action: normalizeAction(raw), provider, providers: getProviderStatus(), aiSessionId: reservation.sessionId, creditReused: reservation.reused, frame: frameStatus(req.authUser.id), liveToolMode: allowToolOnly && images.length === 0, freeOnly: true, usage: { creditsRemaining: reservation.user.creditsRemaining } }); }
    catch (error) { if (!reservation.reused) { const refunded = await refundCredit(req.authUser.id); reservation = null; return providerFailure(res, error, "FREE_AI_UPSTREAM_ERROR", { usage: { creditsRemaining: refunded?.creditsRemaining ?? 0 } }); } return providerFailure(res, error, "FREE_AI_UPSTREAM_ERROR", { usage: { creditsRemaining: reservation.user.creditsRemaining } }); }
  } catch (error) { console.error("GameVision automation error:", error?.message || error); if (reservation && !reservation.reused) { try { await refundCredit(req.authUser.id); } catch (refundError) { console.error("Unable to refund failed automation reservation:", refundError?.message || refundError); } } return res.status(502).json({ error: "Unable to plan the next action right now. Please retry.", code: "AUTOMATION_FAILED", retryable: true, freeOnly: true }); }
});

app.use((err, req, res, next) => { console.error("GameVision API error:", err); res.status(500).json({ error: "Internal server error" }); });
app.listen(PORT, "0.0.0.0", () => { console.log(`GameVision API listening on port ${PORT} (FREE-FIRST)`); if (!process.env.DATABASE_URL) console.warn("DATABASE_URL is missing; account authentication is disabled until configured."); else ensureAuthSchema().then(() => console.log("GameVision account database ready")).catch((error) => console.error("GameVision account database startup check failed:", error?.message || error)); });
