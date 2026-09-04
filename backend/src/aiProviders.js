import { buildAssistantPrompt, buildAutomationPrompt } from "./assistant.js";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY?.trim() || "";
const OPENROUTER_MODEL = "openrouter/free";
// OpenRouter currently accepts at most 3 entries in the `models` fallback array.
// Keep every entry explicitly free so fallback never selects a paid model.
const FREE_MODELS = [
  "openrouter/free",
  "google/gemma-4-26b-a4b-it:free",
  "google/gemma-4-31b-it-20260402:free"
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
  if (!text) throw new Error(`${provider} returned no JSON text`);
  try { return JSON.parse(text); } catch {
    const fenced = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)?.[1];
    if (fenced) return JSON.parse(fenced);
    throw new Error(`${provider} returned invalid JSON`);
  }
}

function imagesToOpenRouterContent(images) { return (Array.isArray(images) ? images : []).map((image) => ({ type: "image_url", image_url: { url: `data:${image.mimeType};base64,${image.data}` } })); }

function nextFreeModels() {
  const start = rotationIndex++ % FREE_MODELS.length;
  return [...FREE_MODELS.slice(start), ...FREE_MODELS.slice(0, start)];
}

async function postOpenRouter(prompt, images, maxTokens = 650) {
  const content = [{ type: "text", text: prompt }, ...imagesToOpenRouterContent(images)];
  const models = nextFreeModels();
  const response = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${OPENROUTER_API_KEY}`, "HTTP-Referer": "https://gamevision.app", "X-Title": "GameVision" },
    signal: AbortSignal.timeout(12000),
    body: JSON.stringify({
      models,
      messages: [{ role: "user", content }],
      response_format: { type: "json_object" },
      temperature: 0.1,
      max_tokens: maxTokens,
      provider: { allow_fallbacks: true, sort: "latency" }
    })
  });
  if (!response.ok) { const detail = await response.text(); console.error("OpenRouter free request failed", response.status, detail.slice(0, 500)); throw new Error(`OpenRouter free upstream error ${response.status}`); }
  const data = await response.json();
  const text = data?.choices?.[0]?.message?.content;
  return parseJsonText(text, "OpenRouter free");
}

export async function analyzeWithOpenRouter(images) {
  if (!providerStatus.openrouterConfigured) throw new Error("OpenRouter is not configured");
  return postOpenRouter(analysisPrompt, images, 650);
}

export async function askWithOpenRouter(images, instruction, history = [], visionFresh = true) {
  if (!providerStatus.openrouterConfigured) throw new Error("OpenRouter is not configured");
  return postOpenRouter(`${buildAssistantPrompt(instruction, history, Array.isArray(images) && images.length > 0, visionFresh)}\n\nReturn ONLY valid JSON with keys answer and confidence.`, images, 650);
}

export async function decideWithOpenRouter(images, goal, history = []) {
  if (!providerStatus.openrouterConfigured) throw new Error("OpenRouter is not configured");
  return postOpenRouter(`${buildAutomationPrompt(goal, history)}\n\nReturn ONLY valid JSON with keys type, x, y, x2, y2, text, durationMs, waitMs, reason, confidence, verify, and stopReason.`, images, 500);
}
