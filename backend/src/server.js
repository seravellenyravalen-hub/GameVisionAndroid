import express from "express";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";
import { buildAssistantPrompt, isScreenDependentInstruction, normalizeAction, normalizeAssistantReply, normalizeHistory } from "./assistant.js";
import { analyzeWithGemini, analyzeWithOpenAI, analyzeWithOpenRouter, askWithGemini, askWithOpenAI, askWithOpenRouter, decideWithGemini, decideWithOpenAI, decideWithOpenRouter, providerStatus } from "./aiProviders.js";

const app = express();
const PORT = process.env.PORT || 3000;
const PROVIDER_COOLDOWN_MS = 5 * 60 * 1000;
const MAX_FRAME_AGE_MS = 15000;
const cooldownUntil = { openrouter: 0, openai: 0, gemini: 0 };
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

function rememberProviderError(provider, error) {
  const message = String(error?.message || "");
  if (/429|rate.?limit|quota|402|insufficient.?credits|credit.?balance/i.test(message)) {
    cooldownUntil[provider] = Date.now() + PROVIDER_COOLDOWN_MS;
    console.warn(`${provider} temporarily disabled after quota/credit/rate-limit response; fallback providers remain active.`);
  }
}

function providerAvailable(provider) {
  return Boolean(providerStatus[`${provider}Configured`]) && Date.now() >= cooldownUntil[provider];
}

function frameAgeMs() { return latestFrame ? Math.max(0, Date.now() - latestFrame.capturedAt) : null; }
function frameStatus() { return { sequence: latestFrame?.sequence || 0, capturedAt: latestFrame?.capturedAt || null, ageMs: frameAgeMs(), fresh: Boolean(latestFrame && (Date.now() - latestFrame.capturedAt) <= MAX_FRAME_AGE_MS) }; }

function requireFreshFrame(res, minSequence = 0) {
  if (!latestFrame) { res.status(409).json({ error: "Waiting for the first screen capture", code: "FRAME_NEEDED", frame: frameStatus() }); return false; }
  if (latestFrame.sequence <= minSequence) { res.status(409).json({ error: "Waiting for a newer screen capture", code: "FRESH_FRAME_NEEDED", frame: frameStatus(), minSequence }); return false; }
  if (Date.now() - latestFrame.capturedAt > MAX_FRAME_AGE_MS) { res.status(409).json({ error: "Waiting for a fresh screen capture", code: "STALE_FRAME", frame: frameStatus() }); return false; }
  return true;
}

function primaryProvider() {
  if (providerAvailable("openrouter")) return "openrouter";
  if (providerAvailable("openai")) return "openai";
  if (providerAvailable("gemini")) return "gemini";
  return null;
}

app.get("/health", (req, res) => {
  const primary = primaryProvider();
  res.status(200).json({
    status: "healthy", service: "gamevision-api",
    aiConfigured: Boolean(primary),
    openrouterConfigured: providerStatus.openrouterConfigured,
    openrouterAvailable: providerAvailable("openrouter"),
    openrouterModel: providerStatus.openrouterModel,
    openaiConfigured: providerStatus.openaiConfigured,
    openaiAvailable: providerAvailable("openai"),
    openaiModel: providerStatus.openaiModel,
    geminiConfigured: providerStatus.geminiConfigured,
    geminiAvailable: providerAvailable("gemini"),
    geminiModel: providerStatus.geminiModel,
    primaryProvider: primary,
    model: primary ? providerStatus[`${primary}Model`] : null,
    frame: frameStatus(), timestamp: new Date().toISOString()
  });
});

app.get("/", (req, res) => res.json({ service: "GameVision API", status: "online", aiConfigured: Boolean(primaryProvider()), openrouterConfigured: providerStatus.openrouterConfigured, openrouterModel: providerStatus.openrouterModel, openaiConfigured: providerStatus.openaiConfigured, geminiConfigured: providerStatus.geminiConfigured, primaryProvider: primaryProvider(), frame: frameStatus() }));
app.get("/api/frame-status", (req, res) => res.json(frameStatus()));

app.post("/api/analyze-frame", async (req, res) => {
  try {
    const images = normalizeImages(req.body);
    if (!images.length) return res.status(400).json({ error: "Image payload required", code: "IMAGE_REQUIRED" });
    if (!primaryProvider()) return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });
    const sequence = ++frameSequence;
    latestFrame = { images, capturedAt: Date.now(), sequence };

    const primary = primaryProvider();
    const secondary = primary === "openrouter"
      ? (providerAvailable("gemini") ? "gemini" : providerAvailable("openai") ? "openai" : null)
      : primary === "openai"
        ? (providerAvailable("gemini") ? "gemini" : null)
        : null;

    const call = (provider) => provider === "openrouter" ? analyzeWithOpenRouter(images) : provider === "openai" ? analyzeWithOpenAI(images) : analyzeWithGemini(images);
    const attempts = await Promise.allSettled([call(primary), ...(secondary ? [call(secondary)] : [])]);
    const rawPrimary = attempts[0]?.status === "fulfilled" ? attempts[0].value : null;
    const rawSecondary = attempts[1]?.status === "fulfilled" ? attempts[1].value : null;
    if (attempts[0]?.status === "rejected") rememberProviderError(primary, attempts[0].reason);
    if (attempts[1]?.status === "rejected") rememberProviderError(secondary, attempts[1].reason);
    if (!rawPrimary && !rawSecondary) return res.status(502).json({ error: "AI analysis service unavailable", code: "AI_UPSTREAM_ERROR", frame: frameStatus() });

    const merged = mergeProviderResults(
      rawPrimary ? normalizeProviderResult(rawPrimary, primary) : null,
      rawSecondary ? normalizeProviderResult(rawSecondary, secondary) : null
    );
    return res.json({ analysis: merged, providers: { primary, secondary, primaryOk: Boolean(rawPrimary), secondaryOk: Boolean(rawSecondary), agreement: merged.agreement }, activeProvider: merged.provider, frame: frameStatus() });
  } catch (error) {
    console.error("GameVision frame analysis error:", error?.message || error);
    return res.status(502).json({ error: "Unable to analyze frame", code: "AI_ANALYSIS_FAILED" });
  }
});

async function askProvider(provider, images, instruction, history, visionFresh) {
  if (provider === "openrouter") return askWithOpenRouter(images, instruction, history, visionFresh);
  if (provider === "openai") return askWithOpenAI(images, instruction, history, visionFresh);
  return askWithGemini(images, instruction, history, visionFresh);
}

app.post("/api/ask", async (req, res) => {
  try {
    const instruction = String(req.body?.instruction || "").trim();
    if (!instruction) return res.status(400).json({ error: "Instruction required", code: "INSTRUCTION_REQUIRED" });
    if (!primaryProvider()) return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });
    const history = normalizeHistory(req.body?.messages);
    const visualRequest = isScreenDependentInstruction(instruction);
    const hasFrame = Boolean(latestFrame);
    const hasFreshFrame = Boolean(latestFrame && Date.now() - latestFrame.capturedAt <= MAX_FRAME_AGE_MS);
    if (visualRequest && !hasFrame) return res.status(409).json({ error: "Waiting for the first screen capture", code: "FRAME_NEEDED", frame: frameStatus() });
    const images = hasFrame ? latestFrame.images : [];
    let raw = null; let provider = null;
    for (const candidate of ["openrouter", "openai", "gemini"]) {
      if (!providerAvailable(candidate)) continue;
      try { raw = await askProvider(candidate, images, instruction, history, hasFreshFrame); provider = candidate; break; }
      catch (error) { rememberProviderError(candidate, error); console.error(`${candidate} assistant unavailable:`, error?.message || error); }
    }
    if (!raw) return res.status(502).json({ error: "Assistant AI unavailable", code: "AI_UPSTREAM_ERROR" });
    return res.json({ reply: normalizeAssistantReply(raw), provider, visionUsed: images.length > 0, visionFresh: hasFreshFrame, frame: frameStatus(), instruction: buildAssistantPrompt(instruction, history, images.length > 0, hasFreshFrame).split("CURRENT USER MESSAGE:\n")[1] || instruction });
  } catch (error) { console.error("GameVision assistant error:", error?.message || error); return res.status(502).json({ error: "Unable to answer instruction", code: "ASSISTANT_FAILED" }); }
});

async function decideProvider(provider, images, goal, history) {
  if (provider === "openrouter") return decideWithOpenRouter(images, goal, history);
  if (provider === "openai") return decideWithOpenAI(images, goal, history);
  return decideWithGemini(images, goal, history);
}

app.post("/api/automation/decide", async (req, res) => {
  try {
    const goal = String(req.body?.goal || "").trim();
    if (!goal) return res.status(400).json({ error: "Automation goal required", code: "GOAL_REQUIRED" });
    const minSequence = Number(req.body?.minFrameSequence) || 0;
    if (!requireFreshFrame(res, minSequence)) return;
    if (!primaryProvider()) return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });
    const history = normalizeHistory(req.body?.messages);
    let raw = null; let provider = null;
    for (const candidate of ["openrouter", "openai", "gemini"]) {
      if (!providerAvailable(candidate)) continue;
      try { raw = await decideProvider(candidate, latestFrame.images, goal, history); provider = candidate; break; }
      catch (error) { rememberProviderError(candidate, error); console.error(`${candidate} action planner unavailable:`, error?.message || error); }
    }
    if (!raw) return res.status(502).json({ error: "Action planner unavailable", code: "ACTION_AI_UNAVAILABLE" });
    return res.json({ action: normalizeAction(raw), provider, frame: frameStatus() });
  } catch (error) { console.error("GameVision automation error:", error?.message || error); return res.status(502).json({ error: "Unable to plan action", code: "AUTOMATION_FAILED" }); }
});

app.use((err, req, res, next) => { console.error("GameVision API error:", err); res.status(500).json({ error: "Internal server error" }); });
app.listen(PORT, "0.0.0.0", () => console.log(`GameVision API listening on port ${PORT}`));
