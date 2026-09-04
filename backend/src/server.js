import express from "express";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";
import { buildAssistantPrompt, isScreenDependentInstruction, normalizeAction, normalizeAssistantReply, normalizeHistory } from "./assistant.js";
import { analyzeWithOpenRouter, askWithOpenRouter, decideWithOpenRouter, providerStatus } from "./aiProviders.js";

const app = express();
const PORT = process.env.PORT || 3000;
const PROVIDER_COOLDOWN_MS = 60 * 1000;
const MAX_FRAME_AGE_MS = 15000;
let openrouterCooldownUntil = 0;
let latestFrame = null;
let frameSequence = 0;

app.use(express.json({ limit: "12mb" }));
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept, User-Agent");
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
  if (/429|rate.?limit|quota|402|insufficient.?credits|credit.?balance/i.test(message)) {
    openrouterCooldownUntil = Date.now() + PROVIDER_COOLDOWN_MS;
    console.warn("OpenRouter free route temporarily rate-limited; free model rotation remains enabled.");
  }
}

function openrouterAvailable() { return providerStatus.openrouterConfigured && Date.now() >= openrouterCooldownUntil; }
function frameAgeMs() { return latestFrame ? Math.max(0, Date.now() - latestFrame.capturedAt) : null; }
function frameStatus() { return { sequence: latestFrame?.sequence || 0, capturedAt: latestFrame?.capturedAt || null, ageMs: frameAgeMs(), fresh: Boolean(latestFrame && Date.now() - latestFrame.capturedAt <= MAX_FRAME_AGE_MS) }; }

function requireFreshFrame(res, minSequence = 0) {
  if (!latestFrame) { res.status(409).json({ error: "Waiting for the first screen capture", code: "FRAME_NEEDED", frame: frameStatus() }); return false; }
  if (latestFrame.sequence <= minSequence) { res.status(409).json({ error: "Waiting for a newer screen capture", code: "FRESH_FRAME_NEEDED", frame: frameStatus(), minSequence }); return false; }
  if (Date.now() - latestFrame.capturedAt > MAX_FRAME_AGE_MS) { res.status(409).json({ error: "Waiting for a fresh screen capture", code: "STALE_FRAME", frame: frameStatus() }); return false; }
  return true;
}

app.get("/health", (req, res) => {
  const configured = Boolean(providerStatus.openrouterConfigured);
  const available = openrouterAvailable();
  res.status(200).json({
    status: "healthy", service: "gamevision-api",
    aiConfigured: configured,
    aiAvailable: available,
    freeOnly: true,
    provider: "openrouter",
    openrouterConfigured: configured,
    openrouterAvailable: available,
    openrouterModel: providerStatus.openrouterModel,
    primaryProvider: available ? "openrouter" : null,
    model: providerStatus.openrouterModel,
    frame: frameStatus(), timestamp: new Date().toISOString()
  });
});

app.get("/", (req, res) => res.json({ service: "GameVision API", status: "online", aiConfigured: Boolean(providerStatus.openrouterConfigured), aiAvailable: openrouterAvailable(), freeOnly: true, provider: "openrouter", openrouterModel: providerStatus.openrouterModel, frame: frameStatus() }));
app.get("/api/frame-status", (req, res) => res.json(frameStatus()));

app.post("/api/analyze-frame", async (req, res) => {
  try {
    const images = normalizeImages(req.body);
    if (!images.length) return res.status(400).json({ error: "Image payload required", code: "IMAGE_REQUIRED" });
    if (!providerStatus.openrouterConfigured) return res.status(503).json({ error: "Free AI is not configured", code: "AI_NOT_CONFIGURED", freeOnly: true });
    const sequence = ++frameSequence;
    latestFrame = { images, capturedAt: Date.now(), sequence };
    if (!openrouterAvailable()) return res.status(429).json({ error: "Free AI is temporarily rate-limited", code: "FREE_AI_RATE_LIMITED", frame: frameStatus() });
    try {
      const raw = await analyzeWithOpenRouter(images);
      const analysis = mergeProviderResults(normalizeProviderResult(raw, "openrouter"), null);
      return res.json({ analysis, providers: { primary: "openrouter", secondary: null, primaryOk: true, secondaryOk: false, agreement: null }, activeProvider: "openrouter", frame: frameStatus(), freeOnly: true });
    } catch (error) {
      rememberOpenRouterError(error);
      return res.status(502).json({ error: "Free AI temporarily unavailable", code: "FREE_AI_UPSTREAM_ERROR", frame: frameStatus(), freeOnly: true });
    }
  } catch (error) {
    console.error("GameVision frame analysis error:", error?.message || error);
    return res.status(502).json({ error: "Unable to analyze frame", code: "AI_ANALYSIS_FAILED", freeOnly: true });
  }
});

app.post("/api/ask", async (req, res) => {
  try {
    const instruction = String(req.body?.instruction || "").trim();
    if (!instruction) return res.status(400).json({ error: "Instruction required", code: "INSTRUCTION_REQUIRED" });
    if (!providerStatus.openrouterConfigured) return res.status(503).json({ error: "Free AI is not configured", code: "AI_NOT_CONFIGURED", freeOnly: true });
    const history = normalizeHistory(req.body?.messages);
    const visualRequest = isScreenDependentInstruction(instruction);
    const hasFrame = Boolean(latestFrame);
    const hasFreshFrame = Boolean(latestFrame && Date.now() - latestFrame.capturedAt <= MAX_FRAME_AGE_MS);
    if (visualRequest && !hasFrame) return res.status(409).json({ error: "Waiting for the first screen capture", code: "FRAME_NEEDED", frame: frameStatus() });
    if (!openrouterAvailable()) return res.status(429).json({ error: "Free AI is temporarily rate-limited; retry after cooldown", code: "FREE_AI_RATE_LIMITED", frame: frameStatus(), freeOnly: true });
    try {
      const images = hasFrame ? latestFrame.images : [];
      const raw = await askWithOpenRouter(images, instruction, history, hasFreshFrame);
      return res.json({ reply: normalizeAssistantReply(raw), provider: "openrouter", visionUsed: images.length > 0, visionFresh: hasFreshFrame, frame: frameStatus(), freeOnly: true, instruction: buildAssistantPrompt(instruction, history, images.length > 0, hasFreshFrame).split("CURRENT USER MESSAGE:\n")[1] || instruction });
    } catch (error) {
      rememberOpenRouterError(error);
      return res.status(502).json({ error: "Free AI temporarily unavailable", code: "FREE_AI_UPSTREAM_ERROR", freeOnly: true });
    }
  } catch (error) { console.error("GameVision assistant error:", error?.message || error); return res.status(502).json({ error: "Unable to answer instruction", code: "ASSISTANT_FAILED", freeOnly: true }); }
});

app.post("/api/automation/decide", async (req, res) => {
  try {
    const goal = String(req.body?.goal || "").trim();
    if (!goal) return res.status(400).json({ error: "Automation goal required", code: "GOAL_REQUIRED" });
    const minSequence = Number(req.body?.minFrameSequence) || 0;
    if (!requireFreshFrame(res, minSequence)) return;
    if (!providerStatus.openrouterConfigured) return res.status(503).json({ error: "Free AI is not configured", code: "AI_NOT_CONFIGURED", freeOnly: true });
    if (!openrouterAvailable()) return res.status(429).json({ error: "Free AI is temporarily rate-limited; retry after cooldown", code: "FREE_AI_RATE_LIMITED", frame: frameStatus(), freeOnly: true });
    const history = normalizeHistory(req.body?.messages);
    try {
      const raw = await decideWithOpenRouter(latestFrame.images, goal, history);
      return res.json({ action: normalizeAction(raw), provider: "openrouter", frame: frameStatus(), freeOnly: true });
    } catch (error) {
      rememberOpenRouterError(error);
      return res.status(502).json({ error: "Free action planner temporarily unavailable", code: "FREE_AI_UPSTREAM_ERROR", freeOnly: true });
    }
  } catch (error) { console.error("GameVision automation error:", error?.message || error); return res.status(502).json({ error: "Unable to plan action", code: "AUTOMATION_FAILED", freeOnly: true }); }
});

app.use((err, req, res, next) => { console.error("GameVision API error:", err); res.status(500).json({ error: "Internal server error" }); });
app.listen(PORT, "0.0.0.0", () => console.log(`GameVision API listening on port ${PORT} (FREE ONLY)`));
