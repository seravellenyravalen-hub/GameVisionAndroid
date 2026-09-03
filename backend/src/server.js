import express from "express";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";
import { buildAssistantPrompt, normalizeAction, normalizeAssistantReply, normalizeHistory } from "./assistant.js";
import { analyzeWithGemini, analyzeWithOpenAI, askWithGemini, askWithOpenAI, decideWithGemini, decideWithOpenAI, providerStatus } from "./aiProviders.js";

const app = express();
const PORT = process.env.PORT || 3000;
const OPENAI_COOLDOWN_MS = 5 * 60 * 1000;
const MAX_FRAME_AGE_MS = 15000;
let openaiCooldownUntil = 0;
let latestFrame = null;

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

function rememberOpenAIError(error) {
  if (String(error?.message || "").includes("429")) {
    openaiCooldownUntil = Date.now() + OPENAI_COOLDOWN_MS;
    console.warn("OpenAI temporarily disabled after quota/rate-limit response; Gemini remains active.");
  }
}

function availableProvider() {
  return providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil ? "openai" : providerStatus.geminiConfigured ? "gemini" : null;
}

app.get("/health", (req, res) => {
  const openaiAvailable = providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil;
  res.status(200).json({ status: "healthy", service: "gamevision-api", aiConfigured: providerStatus.openaiConfigured || providerStatus.geminiConfigured, openaiConfigured: providerStatus.openaiConfigured, geminiConfigured: providerStatus.geminiConfigured, openaiAvailable, primaryProvider: openaiAvailable ? "openai" : "gemini", openaiModel: providerStatus.openaiModel, geminiModel: providerStatus.geminiModel, model: openaiAvailable ? providerStatus.openaiModel : providerStatus.geminiModel, timestamp: new Date().toISOString() });
});

app.get("/", (req, res) => res.json({ service: "GameVision API", status: "online", aiConfigured: providerStatus.openaiConfigured || providerStatus.geminiConfigured, openaiConfigured: providerStatus.openaiConfigured, geminiConfigured: providerStatus.geminiConfigured, primaryProvider: availableProvider(), openaiModel: providerStatus.openaiModel, geminiModel: providerStatus.geminiModel }));

app.post("/api/analyze-frame", async (req, res) => {
  try {
    const images = normalizeImages(req.body);
    if (!images.length) return res.status(400).json({ error: "Image payload required", code: "IMAGE_REQUIRED" });
    if (!providerStatus.openaiConfigured && !providerStatus.geminiConfigured) return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });
    latestFrame = { images, capturedAt: Date.now() };
    const useOpenAI = providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil;
    const attempts = await Promise.allSettled([
      useOpenAI ? analyzeWithOpenAI(images) : Promise.reject(new Error("OpenAI temporarily unavailable; using Gemini")),
      providerStatus.geminiConfigured ? analyzeWithGemini(images) : Promise.reject(new Error("Gemini is not configured"))
    ]);
    const openaiRaw = attempts[0].status === "fulfilled" ? attempts[0].value : null;
    const geminiRaw = attempts[1].status === "fulfilled" ? attempts[1].value : null;
    if (attempts[0].status === "rejected" && useOpenAI) rememberOpenAIError(attempts[0].reason);
    if (!openaiRaw && !geminiRaw) return res.status(502).json({ error: "AI analysis service unavailable", code: "AI_UPSTREAM_ERROR" });
    const merged = mergeProviderResults(openaiRaw ? normalizeProviderResult(openaiRaw, "openai") : null, geminiRaw ? normalizeProviderResult(geminiRaw, "gemini") : null);
    return res.json({ analysis: merged, providers: { openai: Boolean(openaiRaw), gemini: Boolean(geminiRaw), agreement: merged.agreement }, activeProvider: merged.provider });
  } catch (error) {
    console.error("GameVision frame analysis error:", error?.message || error);
    return res.status(502).json({ error: "Unable to analyze frame", code: "AI_ANALYSIS_FAILED" });
  }
});

function requireFreshFrame(res) {
  if (!latestFrame) { res.status(409).json({ error: "No captured frame is available yet", code: "NO_FRAME" }); return false; }
  if (Date.now() - latestFrame.capturedAt > MAX_FRAME_AGE_MS) { res.status(409).json({ error: "The latest screen is stale; waiting for a fresh capture", code: "STALE_FRAME" }); return false; }
  return true;
}

app.post("/api/ask", async (req, res) => {
  try {
    const instruction = String(req.body?.instruction || "").trim();
    if (!instruction) return res.status(400).json({ error: "Instruction required", code: "INSTRUCTION_REQUIRED" });
    if (!requireFreshFrame(res)) return;
    if (!providerStatus.openaiConfigured && !providerStatus.geminiConfigured) return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });
    const history = normalizeHistory(req.body?.messages);
    let raw = null; let provider = null;
    if (providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil) {
      try { raw = await askWithOpenAI(latestFrame.images, instruction, history); provider = "openai"; } catch (error) { rememberOpenAIError(error); console.error("OpenAI assistant unavailable:", error?.message || error); }
    }
    if (!raw && providerStatus.geminiConfigured) { raw = await askWithGemini(latestFrame.images, instruction, history); provider = "gemini"; }
    if (!raw) return res.status(502).json({ error: "Assistant AI unavailable", code: "AI_UPSTREAM_ERROR" });
    return res.json({ reply: normalizeAssistantReply(raw), provider, instruction: buildAssistantPrompt(instruction, history).split("CURRENT USER MESSAGE:\n")[1] || instruction });
  } catch (error) { console.error("GameVision assistant error:", error?.message || error); return res.status(502).json({ error: "Unable to answer instruction", code: "ASSISTANT_FAILED" }); }
});

app.post("/api/automation/decide", async (req, res) => {
  try {
    const goal = String(req.body?.goal || "").trim();
    if (!goal) return res.status(400).json({ error: "Automation goal required", code: "GOAL_REQUIRED" });
    if (!requireFreshFrame(res)) return;
    if (!providerStatus.openaiConfigured && !providerStatus.geminiConfigured) return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });
    const history = normalizeHistory(req.body?.messages);
    let raw = null; let provider = null;
    if (providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil) {
      try { raw = await decideWithOpenAI(latestFrame.images, goal, history); provider = "openai"; } catch (error) { rememberOpenAIError(error); console.error("OpenAI action planner unavailable:", error?.message || error); }
    }
    if (!raw && providerStatus.geminiConfigured) { raw = await decideWithGemini(latestFrame.images, goal, history); provider = "gemini"; }
    if (!raw) return res.status(502).json({ error: "Action planner unavailable", code: "ACTION_AI_UNAVAILABLE" });
    return res.json({ action: normalizeAction(raw), provider, frameAgeMs: Date.now() - latestFrame.capturedAt });
  } catch (error) { console.error("GameVision automation error:", error?.message || error); return res.status(502).json({ error: "Unable to plan action", code: "AUTOMATION_FAILED" }); }
});

app.use((err, req, res, next) => { console.error("GameVision API error:", err); res.status(500).json({ error: "Internal server error" }); });
app.listen(PORT, "0.0.0.0", () => console.log(`GameVision API listening on port ${PORT}`));
