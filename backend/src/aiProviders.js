import { buildAssistantPrompt, buildAutomationPrompt } from "./assistant.js";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY?.trim() || "";
const GEMINI_API_KEY = process.env.GEMINI_API_KEY?.trim() || "";
const OPENAI_API_KEY = process.env.OPENAI_API_KEY?.trim() || "";
const OPENROUTER_MODEL = process.env.OPENROUTER_MODEL?.trim() || "openrouter/free";
const GEMINI_MODEL = process.env.GEMINI_MODEL?.trim() || "gemini-2.5-flash-lite";
const OPENAI_MODEL = process.env.OPENAI_MODEL?.trim() || "gpt-5.6-luna";
const FREE_MODELS = [
  "openrouter/free",
  "google/gemma-4-31b-it:free",
  "google/gemma-4-26b-a4b-it:free",
  "minimax/minimax-m3:free"
];
const PROVIDER_NAMES = ["gemini", "openai", "openrouter"];
const PROVIDER_ORDER = String(process.env.AI_PROVIDER_ORDER || "gemini,openai,openrouter")
  .split(",").map((value) => value.trim().toLowerCase()).filter((value, index, list) => PROVIDER_NAMES.includes(value) && list.indexOf(value) === index);
let rotationIndex = 0;
const providerCooldownUntil = new Map();
let lastProvider = null;

export function isUsableApiKey(value) {
  const key = String(value || "").trim();
  if (!key || /\$\{[^}]+\}/.test(key) || /^\$[A-Z0-9_]+$/.test(key)) return false;
  if (/^(replace|your|put|set|insert)[ _-]*(with|the)?/i.test(key)) return false;
  return true;
}

export function isUsableOpenRouterKey(value) {
  const key = String(value || "").trim();
  if (!isUsableApiKey(key)) return false;
  if (/OPENROUTER_API_KEY/i.test(key) && key.length < 60) return false;
  return true;
}

const configured = {
  gemini: isUsableApiKey(GEMINI_API_KEY),
  openai: isUsableApiKey(OPENAI_API_KEY),
  openrouter: isUsableOpenRouterKey(OPENROUTER_API_KEY)
};

export const providerStatus = {
  geminiConfigured: configured.gemini,
  openaiConfigured: configured.openai,
  openrouterConfigured: configured.openrouter,
  geminiModel: GEMINI_MODEL,
  openaiModel: OPENAI_MODEL,
  openrouterModel: OPENROUTER_MODEL,
  openrouterFreeOnly: true,
  providers: PROVIDER_NAMES.filter((name) => configured[name]),
  lastProvider: null
};

console.log("GameVision AI configuration:", {
  providers: providerStatus.providers,
  geminiConfigured: configured.gemini,
  openaiConfigured: configured.openai,
  openrouterConfigured: configured.openrouter,
  geminiModel: GEMINI_MODEL,
  openaiModel: OPENAI_MODEL,
  openrouterModel: OPENROUTER_MODEL,
  openrouterFreeOnly: true
});

export const screenSchema = {
  type: "object",
  properties: {
    summary: { type: "string" }, state: { type: "string" }, confidence: { type: "number" },
    elements: { type: "array", items: { type: "object", properties: { label: { type: "string" }, x: { type: "number" }, y: { type: "number" }, width: { type: "number" }, height: { type: "number" }, confidence: { type: "number" } }, required: ["label", "x", "y", "width", "height", "confidence"], additionalProperties: false } },
    notes: { type: "array", items: { type: "string" } }
  },
  required: ["summary", "state", "confidence", "elements", "notes"], additionalProperties: false
};

export const assistantSchema = {
  type: "object",
  properties: { answer: { type: "string" }, confidence: { type: "number" } },
  required: ["answer", "confidence"], additionalProperties: false
};

const ACTION_TYPES = ["TAP", "DOUBLE_TAP", "LONG_PRESS", "SWIPE", "DRAG", "PINCH_IN", "PINCH_OUT", "TWO_FINGER_SWIPE", "TYPE_TEXT", "WAIT", "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "OPEN_APP", "STOP"];

export const actionSchema = {
  type: "object",
  properties: {
    type: { type: "string", enum: ACTION_TYPES },
    x: { type: "number" }, y: { type: "number" }, x2: { type: "number" }, y2: { type: "number" }, text: { type: "string" },
    durationMs: { type: "number" }, waitMs: { type: "number" }, reason: { type: "string" }, confidence: { type: "number" }, verify: { type: "boolean" }, stopReason: { type: "string" }
  },
  required: ["type", "x", "y", "x2", "y2", "text", "durationMs", "waitMs", "reason", "confidence", "verify", "stopReason"], additionalProperties: false
};

export const analysisPrompt = `You are GameVision, a generic visual screen analyzer. Analyze only the supplied screen images. The full image is the coordinate reference; top/middle/bottom regions are detail views of the same screen. Identify visible UI/game elements with approximate normalized boxes from 0 to 1000. Cross-check the full image and regions. Never invent hidden state or text. Be concise. Return ONLY JSON with summary, state, confidence, elements, notes.`;

function parseJsonText(text, provider) {
  if (!text) throw providerError("AI_EMPTY_RESPONSE", 502, `${provider} returned no JSON text`, true);
  try { return JSON.parse(text); } catch {
    const fenced = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)?.[1];
    if (fenced) return JSON.parse(fenced);
    const object = text.match(/\{[\s\S]*\}/)?.[0];
    if (object) return JSON.parse(object);
    throw providerError("AI_INVALID_RESPONSE", 502, `${provider} returned invalid JSON`, true);
  }
}

function providerError(code, status, detail, retryable = false) {
  const error = new Error(String(detail).replace(/[\r\n]+/g, " ").slice(0, 240));
  error.code = code;
  error.status = status;
  error.retryable = retryable;
  return error;
}

function classifyProviderFailure(provider, status, detail = "") {
  const text = String(detail || "");
  if (status === 401 || status === 403 || /invalid.*key|authentication|unauthorized/i.test(text)) return { code: `${provider.toUpperCase()}_AUTH_FAILED`, retryable: false, detail: `${provider} rejected its server key. Check the ${provider === "gemini" ? "GEMINI_API_KEY" : provider === "openai" ? "OPENAI_API_KEY" : "OPENROUTER_API_KEY"} in Render.` };
  if (status === 402 || /insufficient.*credit|payment required|billing/i.test(text)) return { code: `${provider.toUpperCase()}_CREDITS_REQUIRED`, retryable: false, detail: `${provider} reported that this key/model has no usable upstream quota.` };
  if (status === 400) return { code: `${provider.toUpperCase()}_BAD_REQUEST`, retryable: false, detail: `${provider} rejected the request format.` };
  if (status === 408 || /timeout|timed out/i.test(text)) return { code: `${provider.toUpperCase()}_TIMEOUT`, retryable: true, detail: `${provider} did not respond before the timeout.` };
  if (status === 429 || /rate.?limit|quota|resource exhausted/i.test(text)) return { code: `${provider.toUpperCase()}_RATE_LIMITED`, retryable: true, detail: `${provider} is temporarily rate-limited.` };
  if (status >= 500) return { code: `${provider.toUpperCase()}_UPSTREAM_ERROR`, retryable: true, detail: `${provider} upstream returned HTTP ${status}.` };
  return { code: `${provider.toUpperCase()}_UPSTREAM_ERROR`, retryable: true, detail: `${provider} request failed with HTTP ${status}.` };
}

export function classifyOpenRouterFailure(status, detail = "") {
  const text = String(detail || "");
  if (status === 400) return { code: "OPENROUTER_BAD_REQUEST", retryable: false, detail: `OpenRouter rejected the request: ${text.slice(0, 180)}` };
  if (status === 401 || /invalid.*key|authentication/i.test(text)) return { code: "OPENROUTER_AUTH_FAILED", retryable: false, detail: "OpenRouter rejected the server key. Check the OPENROUTER_API_KEY in Render." };
  if (status === 402 || /insufficient.*credit|payment required/i.test(text)) return { code: "OPENROUTER_CREDITS_REQUIRED", retryable: false, detail: "OpenRouter reported insufficient upstream credits for the selected route." };
  if (status === 408 || /timeout|timed out/i.test(text)) return { code: "OPENROUTER_TIMEOUT", retryable: true, detail: "OpenRouter did not respond before the timeout." };
  if (status === 429 || /rate.?limit|quota/i.test(text)) return { code: "FREE_AI_RATE_LIMITED", retryable: true, detail: "The free OpenRouter route is temporarily rate-limited." };
  if (status >= 500) return { code: "FREE_AI_UPSTREAM_ERROR", retryable: true, detail: `OpenRouter upstream returned HTTP ${status}.` };
  return { code: "FREE_AI_UPSTREAM_ERROR", retryable: true, detail: `OpenRouter request failed with HTTP ${status}.` };
}

export function buildProviderOrder(providers, startIndex = 0) {
  const list = Array.isArray(providers) ? providers.filter((name) => PROVIDER_NAMES.includes(name)) : [];
  if (!list.length) return [];
  const start = ((Number(startIndex) || 0) % list.length + list.length) % list.length;
  return [...list.slice(start), ...list.slice(0, start)];
}

function configuredProviderOrder() {
  const base = PROVIDER_ORDER.length ? PROVIDER_ORDER : PROVIDER_NAMES;
  const available = base.filter((name) => configured[name]);
  return buildProviderOrder(available, rotationIndex++);
}

function markProviderSuccess(provider) {
  lastProvider = provider;
  providerStatus.lastProvider = provider;
  providerCooldownUntil.delete(provider);
}

function markProviderFailure(provider, error) {
  if (error?.retryable) providerCooldownUntil.set(provider, Date.now() + Math.min(30000, provider === "openrouter" ? 5000 : 8000));
}

function imagesToOpenRouterContent(images) {
  return (Array.isArray(images) ? images : []).map((image) => ({ type: "image_url", image_url: { url: `data:${image.mimeType};base64,${image.data}` } }));
}

function imagesToGeminiParts(images) {
  return (Array.isArray(images) ? images : []).map((image) => ({ inline_data: { mime_type: image.mimeType, data: image.data } }));
}

function imagesToOpenAIContent(images) {
  return (Array.isArray(images) ? images : []).map((image) => ({ type: "input_image", image_url: `data:${image.mimeType};base64,${image.data}`, detail: "low" }));
}

function nextFreeModels() {
  const start = rotationIndex++ % FREE_MODELS.length;
  return [...FREE_MODELS.slice(start), ...FREE_MODELS.slice(0, start)];
}

async function requestOpenRouter(prompt, images, maxTokens, model) {
  const content = [{ type: "text", text: prompt }, ...imagesToOpenRouterContent(images)];
  const response = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${OPENROUTER_API_KEY}`, "HTTP-Referer": "https://gamevision.app", "X-Title": "GameVision" },
    signal: AbortSignal.timeout(15000),
    body: JSON.stringify({ model, messages: [{ role: "user", content }], response_format: { type: "json_object" }, temperature: 0.1, max_tokens: maxTokens })
  });
  if (response.ok) {
    const data = await response.json();
    return parseJsonText(data?.choices?.[0]?.message?.content, "OpenRouter free");
  }
  const detail = await response.text();
  const classified = classifyOpenRouterFailure(response.status, detail);
  throw providerError(classified.code, response.status, classified.detail, classified.retryable);
}

async function requestGemini(prompt, images, maxTokens) {
  const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(GEMINI_MODEL)}:generateContent`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": GEMINI_API_KEY },
    signal: AbortSignal.timeout(12000),
    body: JSON.stringify({
      contents: [{ role: "user", parts: [{ text: prompt }, ...imagesToGeminiParts(images)] }],
      generationConfig: { temperature: 0.1, maxOutputTokens: maxTokens, responseMimeType: "application/json" }
    })
  });
  if (response.ok) {
    const data = await response.json();
    const text = data?.candidates?.[0]?.content?.parts?.map((part) => part?.text || "").join("");
    return parseJsonText(text, "Gemini");
  }
  const detail = await response.text();
  const classified = classifyProviderFailure("gemini", response.status, detail);
  throw providerError(classified.code, response.status, classified.detail, classified.retryable);
}

async function requestOpenAI(prompt, images, maxTokens) {
  const content = [{ type: "input_text", text: prompt }, ...imagesToOpenAIContent(images)];
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${OPENAI_API_KEY}` },
    signal: AbortSignal.timeout(15000),
    body: JSON.stringify({ model: OPENAI_MODEL, input: [{ role: "user", content }], max_output_tokens: maxTokens })
  });
  if (response.ok) {
    const data = await response.json();
    const text = data?.output_text || data?.output?.flatMap((item) => item?.content || []).map((part) => part?.text || "").join("");
    return parseJsonText(text, "OpenAI");
  }
  const detail = await response.text();
  const classified = classifyProviderFailure("openai", response.status, detail);
  throw providerError(classified.code, response.status, classified.detail, classified.retryable);
}

async function callProvider(provider, prompt, images, maxTokens) {
  if (provider === "gemini") return requestGemini(prompt, images, maxTokens);
  if (provider === "openai") return requestOpenAI(prompt, images, maxTokens);
  return requestOpenRouter(prompt, images, maxTokens, OPENROUTER_MODEL);
}

async function postWithProviderPool(prompt, images, maxTokens = 650) {
  const order = configuredProviderOrder().filter((provider) => Date.now() >= (providerCooldownUntil.get(provider) || 0));
  if (!order.length) throw providerError("AI_NOT_CONFIGURED", 503, "No configured AI provider is currently available.", true);
  let lastError = null;
  for (const provider of order) {
    try {
      const result = await callProvider(provider, prompt, images, maxTokens);
      markProviderSuccess(provider);
      return { result, provider };
    } catch (error) {
      lastError = error;
      markProviderFailure(provider, error);
    }
  }
  throw lastError || providerError("FREE_AI_UPSTREAM_ERROR", 502, "All configured AI providers failed.", true);
}

export function getProviderStatus() {
  return {
    providers: providerStatus.providers,
    configured: providerStatus.providers.length > 0,
    available: providerStatus.providers.filter((name) => Date.now() >= (providerCooldownUntil.get(name) || 0)),
    lastProvider,
    models: { gemini: GEMINI_MODEL, openai: OPENAI_MODEL, openrouter: OPENROUTER_MODEL }
  };
}

export async function analyzeWithProviders(images) { return postWithProviderPool(analysisPrompt, images, 650); }
export async function askWithProviders(images, instruction, history = [], visionFresh = true) { return postWithProviderPool(`${buildAssistantPrompt(instruction, history, Array.isArray(images) && images.length > 0, visionFresh)}\n\nReturn ONLY valid JSON with keys answer and confidence.`, images, 650); }
export async function decideWithProviders(images, goal, history = []) { return postWithProviderPool(`${buildAutomationPrompt(goal, history)}\n\nReturn ONLY valid JSON with keys type, x, y, x2, y2, text, durationMs, waitMs, reason, confidence, verify, and stopReason.`, images, 500); }

export async function analyzeWithOpenRouter(images) { return (await postWithProviderPool(analysisPrompt, images, 650)).result; }
export async function askWithOpenRouter(images, instruction, history = [], visionFresh = true) { return (await postWithProviderPool(`${buildAssistantPrompt(instruction, history, Array.isArray(images) && images.length > 0, visionFresh)}\n\nReturn ONLY valid JSON with keys answer and confidence.`, images, 650)).result; }
export async function decideWithOpenRouter(images, goal, history = []) { return (await postWithProviderPool(`${buildAutomationPrompt(goal, history)}\n\nReturn ONLY valid JSON with keys type, x, y, x2, y2, text, durationMs, waitMs, reason, confidence, verify, and stopReason.`, images, 500)).result; }
