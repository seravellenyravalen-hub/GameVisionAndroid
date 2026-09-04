import express from "express";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";
import { buildAssistantPrompt, isScreenDependentInstruction, normalizeAction, normalizeAssistantReply, normalizeHistory } from "./assistant.js";
import { analyzeWithOpenRouter, askWithOpenRouter, decideWithOpenRouter, providerStatus } from "./aiProviders.js";
import { authMiddleware, consumeCredit, createAccount, getUserForToken, loginAccount, logoutToken, ensureAuthSchema, refundCredit } from "./auth.js";
import { FrameStore } from "./frameStore.js";

const app = express();
const PORT = process.env.PORT || 3000;
const PROVIDER_COOLDOWN_MS = 5 * 1000;
const MAX_FRAME_AGE_MS = 15000;
let openrouterCooldownUntil = 0;
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
  return candidates.filter((image) => image?.data && ["image/jpeg", "image/png", "image/webp"].includes(image.mimeType)).slice(0, 5).map((image) => ({
    data: String(image.data), mimeType: image.mimeType, role: String(image.role || "region"),
    width: Number(image.width) || 0, height: Number(image.height) || 0, x: Number(image.x) || 0, y: Number(image.y) || 0
  }));
}

function rememberOpenRouterError(error) {
  const message = String(error?.message || "");
  if (/429|rate.?limit|quota|402|insufficient.?credits/i.test(message)) {
    openrouterCooldownUntil = Date.now() + PROVIDER_COOLDOWN_MS;
    console.warn("OpenRouter free route temporarily rate-limited; retry window is short and free-model fallback remains enabled.");
  }
}

function openrouterAvailable() { return providerStatus.openrouterConfigured && Date.now() >= openrouterCooldownUntil; }
function frameStatus(userId = null) { return frameStore.status(userId); }

function requireFreshFrame(res, userId, minSequence = 0, minEpoch = null) {
  const frame = frameStore.get(userId);
  const status = frameStatus(userId);
  if (!frame) { res.status(409).json({ error: "Waiting for the first screen capture", code: "FRAME_NEEDED", frame: status }); return false; }
  const epochChanged = minEpoch != null && String(minEpoch) !== frame.serverEpoch;
  if (!epochChanged && frame.sequence <= minSequence) { res.status(409).json({ error: "Waiting for a newer screen capture", code: "FRESH_FRAME_NEEDED", frame: status, minSequence, minEpoch }); return false; }
  if (!frameStore.isFresh(userId, minSequence, minEpoch)) { res.status(409).json({ error: "Waiting for a fresh screen capture", code: "STALE_FRAME", frame: status, minSequence, minEpoch }); return false; }
  return true;
}

async function authReady() {
  if (!process.env.DATABASE_URL) return false;
  try { await ensureAuthSchema(); return true; } catch (error) { console.error("Auth database is not ready:", error?.message || error); return false; }
}

app.get("/health", async (req, res) => {
  const configured = Boolean(providerStatus.openrouterConfigured);
  const available = openrouterAvailable();
  const databaseConfigured = Boolean(process.env.DATABASE_URL);
  const databaseReady = databaseConfigured ? await authReady() : false;
  res.status(200).json({
    status: "healthy", service: "gamevision-api",
    aiConfigured: configured, aiAvailable: available, freeOnly: true,
    provider: "openrouter", openrouterConfigured: configured, openrouterAvailable: available,
    openrouterModel: providerStatus.openrouterModel, primaryProvider: available ? "openrouter" : null,
    model: providerStatus.openrouterModel,
    authConfigured: databaseConfigured, authReady: databaseReady,
    frame: frameStatus(), timestamp: new Date().toISOString()
  });
});

app.get("/", (req, res) => res.json({ service: "GameVision API", status: "online", aiConfigured: Boolean(providerStatus.openrouterConfigured), aiAvailable: openrouterAvailable(), freeOnly: true, provider: "openrouter", openrouterModel: providerStatus.openrouterModel, authConfigured: Boolean(process.env.DATABASE_URL), frame: frameStatus() }));

app.post("/api/auth/signup", async (req, res) => {
  try {
    const user = await createAccount(req.body?.email, req.body?.password);
    const session = await loginAccount(user.email, req.body?.password);
    res.status(201).json({ token: session.token, user: session.user });
  } catch (error) {
    const status = ["INVALID_EMAIL", "ACCOUNT_EXISTS"].includes(error?.code) ? 400 : 503;
    res.status(status).json({ error: error?.message || "Account creation failed", code: error?.code || "AUTH_UNAVAILABLE" });
  }
});

app.post("/api/auth/login", async (req, res) => {
  try {
    const session = await loginAccount(req.body?.email, req.body?.password);
    res.json({ token: session.token, user: session.user });
  } catch (error) {
    const status = error?.code === "INVALID_CREDENTIALS" ? 401 : 503;
    res.status(status).json({ error: error?.message || "Sign in failed", code: error?.code || "AUTH_UNAVAILABLE" });
  }
});

app.get("/api/auth/me", async (req, res) => {
  try {
    const token = String(req.headers.authorization || "").startsWith("Bearer ") ? String(req.headers.authorization).slice(7).trim() : "";
    const user = await getUserForToken(token);
    if (!user) return res.status(401).json({ error: "Sign in required", code: "AUTH_REQUIRED" });
    res.json({ user });
  } catch (error) { res.status(503).json({ error: "Account service unavailable", code: "AUTH_UNAVAILABLE" }); }
});

app.post("/api/auth/logout", async (req, res) => {
  try {
    const token = String(req.headers.authorization || "").startsWith("Bearer ") ? String(req.headers.authorization).slice(7).trim() : "";
    await logoutToken(token);
    res.json({ ok: true });
  } catch (error) { res.status(503).json({ error: "Account service unavailable", code: "AUTH_UNAVAILABLE" }); }
});

const requireAuth = authMiddleware();

app.get("/api/frame-status", requireAuth, (req, res) => res.json({ ...frameStatus(req.authUser.id), user: req.authUser }));

app.post("/api/frame", requireAuth, (req, res) => {
  const images = normalizeImages(req.body);
  if (!images.length) return res.status(400).json({ error: "Image payload required", code: "IMAGE_REQUIRED" });
  const frame = frameStore.put(req.authUser.id, images);
  res.json({ ok: true, frame: { sequence: frame.sequence, capturedAt: frame.capturedAt, ageMs: 0, fresh: true, serverEpoch: frame.serverEpoch } });
});

app.post("/api/analyze-frame", requireAuth, async (req, res) => {
  try {
    const images = normalizeImages(req.body);
    if (!images.length) return res.status(400).json({ error: "Image payload required", code: "IMAGE_REQUIRED" });
    const frame = frameStore.put(req.authUser.id, images);
    if (!providerStatus.openrouterConfigured) return res.status(503).json({ error: "Free AI is not configured. Configure the OpenRouter key in the server environment.", code: "AI_NOT_CONFIGURED", freeOnly: true, frame: frameStatus(req.authUser.id) });
    if (!openrouterAvailable()) return res.status(429).json({ error: "Free AI is temporarily rate-limited", code: "FREE_AI_RATE_LIMITED", frame: frameStatus(req.authUser.id) });
    try {
      const raw = await analyzeWithOpenRouter(images);
      const analysis = mergeProviderResults(normalizeProviderResult(raw, "openrouter"), null);
      return res.json({ analysis, providers: { primary: "openrouter", secondary: null, primaryOk: true, secondaryOk: false, agreement: null }, activeProvider: "openrouter", frame: frameStatus(req.authUser.id), freeOnly: true, usage: { creditsRemaining: req.authUser.creditsRemaining } });
    } catch (error) {
      rememberOpenRouterError(error);
      return res.status(502).json({ error: "Free AI temporarily unavailable. Please retry shortly.", code: "FREE_AI_UPSTREAM_ERROR", retryable: true, frame: frameStatus(req.authUser.id), freeOnly: true });
    }
  } catch (error) {
    console.error("GameVision frame analysis error:", error?.message || error);
    return res.status(502).json({ error: "Unable to analyze frame right now. Please retry.", code: "AI_ANALYSIS_FAILED", retryable: true, freeOnly: true });
  }
});

app.post("/api/ask", requireAuth, async (req, res) => {
  let reservedCredit = false;
  try {
    const instruction = String(req.body?.instruction || "").trim();
    if (!instruction) return res.status(400).json({ error: "Instruction required", code: "INSTRUCTION_REQUIRED" });
    if (!providerStatus.openrouterConfigured) return res.status(503).json({ error: "Free AI is not configured. Configure the OpenRouter key in the server environment.", code: "AI_NOT_CONFIGURED", freeOnly: true });
    if (!openrouterAvailable()) return res.status(429).json({ error: "Free AI is temporarily rate-limited; retry after cooldown", code: "FREE_AI_RATE_LIMITED", frame: frameStatus(req.authUser.id), freeOnly: true });
    const usage = await consumeCredit(req.authUser.id);
    if (!usage.allowed) return res.status(429).json({ error: "Your free GameVision allowance has been used. It will reset automatically.", code: "FREE_ALLOWANCE_EXHAUSTED", resetAt: usage.user.resetAt, creditsRemaining: 0 });
    reservedCredit = true;
    const history = normalizeHistory(req.body?.messages);
    const visualRequest = isScreenDependentInstruction(instruction);
    const frame = frameStore.get(req.authUser.id);
    const hasFrame = Boolean(frame);
    const hasFreshFrame = Boolean(frame && frameStore.isFresh(req.authUser.id));
    if (visualRequest && !hasFreshFrame) {
      const refunded = await refundCredit(req.authUser.id); reservedCredit = false;
      return res.status(409).json({ error: "Waiting for a fresh screen capture", code: "FRESH_FRAME_NEEDED", frame: frameStatus(req.authUser.id), usage: { creditsRemaining: refunded?.creditsRemaining ?? usage.user.creditsRemaining + 1 } });
    }
    try {
      const images = hasFrame ? frame.images : [];
      const raw = await askWithOpenRouter(images, instruction, history, hasFreshFrame);
      return res.json({ reply: normalizeAssistantReply(raw), provider: "openrouter", visionUsed: images.length > 0, visionFresh: hasFreshFrame, frame: frameStatus(req.authUser.id), freeOnly: true, usage: { creditsRemaining: usage.user.creditsRemaining }, instruction: buildAssistantPrompt(instruction, history, images.length > 0, hasFreshFrame).split("CURRENT USER MESSAGE:\n")[1] || instruction });
    } catch (error) {
      rememberOpenRouterError(error);
      const refunded = await refundCredit(req.authUser.id); reservedCredit = false;
      return res.status(502).json({ error: "Free AI temporarily unavailable. Your GameVision credit was preserved. Please retry shortly.", code: "FREE_AI_UPSTREAM_ERROR", retryable: true, usage: { creditsRemaining: refunded?.creditsRemaining ?? usage.user.creditsRemaining }, freeOnly: true });
    }
  } catch (error) {
    console.error("GameVision assistant error:", error?.message || error);
    if (reservedCredit) { try { await refundCredit(req.authUser.id); } catch (refundError) { console.error("Unable to refund failed assistant credit:", refundError?.message || refundError); } }
    return res.status(502).json({ error: "Unable to answer right now. Please retry.", code: "ASSISTANT_FAILED", retryable: true, freeOnly: true });
  }
});

app.post("/api/automation/decide", requireAuth, async (req, res) => {
  let reservedCredit = false;
  try {
    const goal = String(req.body?.goal || "").trim();
    if (!goal) return res.status(400).json({ error: "Automation goal required", code: "GOAL_REQUIRED" });
    const minSequence = Number(req.body?.minFrameSequence) || 0;
    const minEpoch = req.body?.minFrameEpoch == null ? null : String(req.body.minFrameEpoch);
    if (!requireFreshFrame(res, req.authUser.id, minSequence, minEpoch)) return;
    if (!providerStatus.openrouterConfigured) return res.status(503).json({ error: "Free AI is not configured. Configure the OpenRouter key in the server environment.", code: "AI_NOT_CONFIGURED", freeOnly: true });
    if (!openrouterAvailable()) return res.status(429).json({ error: "Free AI is temporarily rate-limited; retry after cooldown", code: "FREE_AI_RATE_LIMITED", frame: frameStatus(req.authUser.id), freeOnly: true });
    const usage = await consumeCredit(req.authUser.id);
    if (!usage.allowed) return res.status(429).json({ error: "Your free GameVision allowance has been used. It will reset automatically.", resetAt: usage.user.resetAt, creditsRemaining: 0 });
    reservedCredit = true;
    const history = normalizeHistory(req.body?.messages);
    const frame = frameStore.get(req.authUser.id);
    try {
      const raw = await decideWithOpenRouter(frame.images, goal, history);
      return res.json({ action: normalizeAction(raw), provider: "openrouter", frame: frameStatus(req.authUser.id), freeOnly: true, usage: { creditsRemaining: usage.user.creditsRemaining } });
    } catch (error) {
      rememberOpenRouterError(error);
      const refunded = await refundCredit(req.authUser.id); reservedCredit = false;
      return res.status(502).json({ error: "Free action planner temporarily unavailable. Your GameVision credit was preserved. Please retry shortly.", code: "FREE_AI_UPSTREAM_ERROR", retryable: true, usage: { creditsRemaining: refunded?.creditsRemaining ?? usage.user.creditsRemaining }, freeOnly: true });
    }
  } catch (error) {
    console.error("GameVision automation error:", error?.message || error);
    if (reservedCredit) { try { await refundCredit(req.authUser.id); } catch (refundError) { console.error("Unable to refund failed automation credit:", refundError?.message || refundError); } }
    return res.status(502).json({ error: "Unable to plan the next action right now. Please retry.", code: "AUTOMATION_FAILED", retryable: true, freeOnly: true });
  }
});

app.use((err, req, res, next) => { console.error("GameVision API error:", err); res.status(500).json({ error: "Internal server error" }); });

app.listen(PORT, "0.0.0.0", () => {
  console.log(`GameVision API listening on port ${PORT} (FREE ONLY)`);
  if (!process.env.DATABASE_URL) console.warn("DATABASE_URL is missing; account authentication is disabled until configured.");
  else ensureAuthSchema().then(() => console.log("GameVision account database ready")).catch((error) => console.error("GameVision account database startup check failed:", error?.message || error));
});
