import express from "express";
import { mergeProviderResults, normalizeProviderResult } from "./analysis.js";
import { buildAssistantPrompt, normalizeAssistantReply } from "./assistant.js";
import { analyzeWithGemini, analyzeWithOpenAI, askWithGemini, askWithOpenAI, providerStatus } from "./aiProviders.js";

const app = express();
const PORT = process.env.PORT || 3000;
const OPENAI_COOLDOWN_MS = 5 * 60 * 1000;
let openaiCooldownUntil = 0;
let latestFrame = null;

app.use(express.json({ limit: "10mb" }));
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept, User-Agent");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

app.get("/health", (req, res) => {
  const openaiAvailable = providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil;
  res.status(200).json({
    status: "healthy",
    service: "gamevision-api",
    aiConfigured: providerStatus.openaiConfigured || providerStatus.geminiConfigured,
    openaiConfigured: providerStatus.openaiConfigured,
    geminiConfigured: providerStatus.geminiConfigured,
    openaiAvailable,
    primaryProvider: openaiAvailable ? "openai" : "gemini",
    openaiModel: providerStatus.openaiModel,
    geminiModel: providerStatus.geminiModel,
    model: openaiAvailable ? providerStatus.openaiModel : providerStatus.geminiModel,
    timestamp: new Date().toISOString()
  });
});

app.get("/", (req, res) => {
  res.json({
    service: "GameVision API",
    status: "online",
    aiConfigured: providerStatus.openaiConfigured || providerStatus.geminiConfigured,
    openaiConfigured: providerStatus.openaiConfigured,
    geminiConfigured: providerStatus.geminiConfigured,
    primaryProvider: providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil ? "openai" : "gemini",
    openaiModel: providerStatus.openaiModel,
    geminiModel: providerStatus.geminiModel
  });
});

function rememberOpenAIError(error) {
  if (String(error?.message || "").includes("429")) {
    openaiCooldownUntil = Date.now() + OPENAI_COOLDOWN_MS;
    console.warn("OpenAI temporarily disabled after quota/rate-limit response; Gemini remains active.");
  }
}

app.post("/api/analyze-frame", async (req, res) => {
  try {
    const image = req.body?.image;
    if (!image?.data || !image?.mimeType) return res.status(400).json({ error: "Image payload required" });
    if (!["image/jpeg", "image/png", "image/webp"].includes(image.mimeType)) return res.status(400).json({ error: "Unsupported image type" });
    if (!providerStatus.openaiConfigured && !providerStatus.geminiConfigured) {
      return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });
    }

    latestFrame = image;
    const useOpenAI = providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil;
    const attempts = await Promise.allSettled([
      useOpenAI ? analyzeWithOpenAI(image) : Promise.reject(new Error("OpenAI temporarily unavailable; using Gemini")),
      providerStatus.geminiConfigured ? analyzeWithGemini(image) : Promise.reject(new Error("Gemini is not configured"))
    ]);

    const openaiRaw = attempts[0].status === "fulfilled" ? attempts[0].value : null;
    const geminiRaw = attempts[1].status === "fulfilled" ? attempts[1].value : null;
    if (attempts[0].status === "rejected" && useOpenAI) {
      rememberOpenAIError(attempts[0].reason);
      console.error("OpenAI analysis unavailable:", attempts[0].reason?.message || attempts[0].reason);
    }
    if (attempts[1].status === "rejected") console.error("Gemini analysis unavailable:", attempts[1].reason?.message || attempts[1].reason);
    if (!openaiRaw && !geminiRaw) return res.status(502).json({ error: "AI analysis service unavailable", code: "AI_UPSTREAM_ERROR" });

    const openaiResult = openaiRaw ? normalizeProviderResult(openaiRaw, "openai") : null;
    const geminiResult = geminiRaw ? normalizeProviderResult(geminiRaw, "gemini") : null;
    const merged = mergeProviderResults(openaiResult, geminiResult);
    return res.json({
      analysis: merged,
      providers: { openai: Boolean(openaiResult), gemini: Boolean(geminiResult), agreement: merged.agreement },
      activeProvider: merged.provider
    });
  } catch (error) {
    console.error("GameVision frame analysis error:", error?.message || error);
    return res.status(502).json({ error: "Unable to analyze frame", code: "AI_ANALYSIS_FAILED" });
  }
});

app.post("/api/ask", async (req, res) => {
  try {
    const instruction = String(req.body?.instruction || "").trim();
    if (!instruction) return res.status(400).json({ error: "Instruction required", code: "INSTRUCTION_REQUIRED" });
    if (!latestFrame) return res.status(409).json({ error: "No captured frame is available yet", code: "NO_FRAME" });
    if (!providerStatus.openaiConfigured && !providerStatus.geminiConfigured) return res.status(503).json({ error: "AI analysis is not configured", code: "AI_NOT_CONFIGURED" });

    const useOpenAI = providerStatus.openaiConfigured && Date.now() >= openaiCooldownUntil;
    let raw;
    let provider;
    if (useOpenAI) {
      try {
        raw = await askWithOpenAI(latestFrame, instruction);
        provider = "openai";
      } catch (error) {
        rememberOpenAIError(error);
        console.error("OpenAI assistant unavailable:", error?.message || error);
      }
    }
    if (!raw && providerStatus.geminiConfigured) {
      raw = await askWithGemini(latestFrame, instruction);
      provider = "gemini";
    }
    if (!raw) return res.status(502).json({ error: "Assistant AI unavailable", code: "AI_UPSTREAM_ERROR" });

    const reply = normalizeAssistantReply(raw);
    return res.json({ reply, provider, instruction: buildAssistantPrompt(instruction).split("User instruction: ")[1] || instruction });
  } catch (error) {
    console.error("GameVision assistant error:", error?.message || error);
    return res.status(502).json({ error: "Unable to answer instruction", code: "ASSISTANT_FAILED" });
  }
});

app.use((err, req, res, next) => {
  console.error("GameVision API error:", err);
  res.status(500).json({ error: "Internal server error" });
});

app.listen(PORT, "0.0.0.0", () => console.log(`GameVision API listening on port ${PORT}`));
