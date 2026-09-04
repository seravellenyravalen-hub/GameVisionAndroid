import { buildAssistantPrompt, buildAutomationPrompt } from "./assistant.js";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY?.trim() || "";
const OPENROUTER_MODEL = "openrouter/free";
const FREE_MODELS = [
  "openrouter/free",
  "google/gemma-4-31b-it:free",
  "google/gemma-4-26b-a4b-it:free",
  "minimax/minimax-m3:free"
];
let rotationIndex = 0;

export function isUsableOpenRouterKey(value) {
  const key = String(value || "").trim();
  if (!key) return false;
  if (/\$\{[^}]+\}/.test(key) || /^\$[A-Z0-9_]+$/.test(key)) return false;
  if (/^(replace|your|put|set|insert)[ _-]*(with|the)?/i.test(key)) return false;
  if (/OPENROUTER_API_KEY/i.test(key) && key.length < 60) return false;
  return true;
}

export const providerStatus = {
  openrouterConfigured: isUsableOpenRouterKey(OPENROUTER_API_KEY),
  openrouterModel: OPENROUTER_MODEL,
  openrouterFreeOnly: true
};

console.log("GameVision AI configuration:", {
  openrouterConfigured: providerStatus.openrouterConfigured,
  openrouterKeyLength: OPENROUTER_API_KEY.length,
  openrouterModel: OPENROUTER_MODEL,
  openrouterFreeOnly: true,
  freeModels: FREE_MODELS
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

const ACTION_TYPES = ["TAP", "DOUBLE_TAP", "LONG_PRESS", "SWIPE", "DRAG", "PINCH_IN", "PINCH_OUT", "TWO_FINGER_SWIPE", "TYPE_TEXT", "WAIT", "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "STOP"];

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

function imagesToOpenRouterContent(images) {
  return (Array.isArray(images) ? images : []).map((image) => ({ type: "image_url", image_url: { url: `data:${image.mimeType};base64,${image.data}` } }));
}

function nextFreeModels() {
  const start = rotationIndex++ % FREE_MODELS.length;
  return [...FREE_MODELS.slice(start), ...FREE_MODELS.slice(0, start)];
}

function providerError(code, status, detail, retryable = false) {
  const error = new Error(String(detail).replace(/[\r\n]+/g, " ").slice(0, 240));
  error.code = code;
  error.status = status;
  error.retryable = retryable;
  return error;
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

async function requestOpenRouter(prompt, images, maxTokens, model) {
  const content = [{ type: "text", text: prompt }, ...imagesToOpenRouterContent(images)];
  const response = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${OPENROUTER_API_KEY}`, "HTTP-Referer": "https://gamevision.app", "X-Title": "GameVision" },
    signal: AbortSignal.timeout(15000),
    body: JSON.stringify({
      model,
      messages: [{ role: "user", content }],
      response_format: { type: "json_object" },
      temperature: 0.1,
      max_tokens: maxTokens
    })
  });
  if (response.ok) {
    const data = await response.json();
    return parseJsonText(data?.choices?.[0]?.message?.content, "OpenRouter free");
  }
  const detail = await response.text();
  const classified = classifyOpenRouterFailure(response.status, detail);
  console.error("OpenRouter free request failed", response.status, classified.code, classified.detail);
  throw providerError(classified.code, response.status, classified.detail, classified.retryable);
}

async function postOpenRouter(prompt, images, maxTokens = 650) {
  if (!providerStatus.openrouterConfigured) throw providerError("AI_NOT_CONFIGURED", 503, "OpenRouter is not configured on the server.", false);

  // Use the official free router as the primary route. It dynamically selects a currently
  // available free model with the capabilities required by the request, including vision
  // and structured output, instead of sending a multi-model request that can be rejected
  // by the upstream API when one model/parameter combination changes.
  const attempts = [OPENROUTER_MODEL, ...nextFreeModels().filter((model) => model !== OPENROUTER_MODEL)];
  let lastError = null;

  for (const model of attempts) {
    try {
      return await requestOpenRouter(prompt, images, maxTokens, model);
    } catch (error) {
      lastError = error;
      if (!error?.retryable && error?.code !== "OPENROUTER_BAD_REQUEST") break;
      if (error?.code === "OPENROUTER_BAD_REQUEST") continue;
      await new Promise((resolve) => setTimeout(resolve, 350));
    }
  }

  throw lastError || providerError("FREE_AI_UPSTREAM_ERROR", 502, "OpenRouter free request failed.", true);
}

export async function analyzeWithOpenRouter(images) {
  return postOpenRouter(analysisPrompt, images, 650);
}

export async function askWithOpenRouter(images, instruction, history = [], visionFresh = true) {
  return postOpenRouter(`${buildAssistantPrompt(instruction, history, Array.isArray(images) && images.length > 0, visionFresh)}\n\nReturn ONLY valid JSON with keys answer and confidence.`, images, 650);
}

export async function decideWithOpenRouter(images, goal, history = []) {
  return postOpenRouter(`${buildAutomationPrompt(goal, history)}\n\nReturn ONLY valid JSON with keys type, x, y, x2, y2, text, durationMs, waitMs, reason, confidence, verify, and stopReason.`, images, 500);
}
