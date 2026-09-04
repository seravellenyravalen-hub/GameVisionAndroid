import { buildAssistantPrompt, buildAutomationPrompt } from "./assistant.js";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY?.trim() || "";
const OPENROUTER_MODEL = process.env.OPENROUTER_MODEL || "openai/gpt-4o";
const OPENAI_API_KEY = process.env.OPENAI_API_KEY?.trim() || "";
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-5.6-sol";
const GEMINI_API_KEY = process.env.GEMINI_API_KEY?.trim() || "";
const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-3.8-flash";

export const providerStatus = {
  openrouterConfigured: OPENROUTER_API_KEY.length > 0,
  openrouterModel: OPENROUTER_MODEL,
  openaiConfigured: OPENAI_API_KEY.length > 0,
  openaiModel: OPENAI_MODEL,
  geminiConfigured: GEMINI_API_KEY.length > 0,
  geminiModel: GEMINI_MODEL
};

console.log("GameVision AI configuration:", {
  openrouterConfigured: providerStatus.openrouterConfigured,
  openrouterKeyLength: OPENROUTER_API_KEY.length,
  openrouterModel: OPENROUTER_MODEL,
  openaiConfigured: providerStatus.openaiConfigured,
  openaiKeyLength: OPENAI_API_KEY.length,
  geminiConfigured: providerStatus.geminiConfigured,
  geminiKeyLength: GEMINI_API_KEY.length,
  openaiModel: OPENAI_MODEL,
  geminiModel: GEMINI_MODEL
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

export const analysisPrompt = `You are GameVision, a generic visual screen analyzer. Analyze the supplied screen images only. Do not assume the screen is a football game, sports game, or any particular genre. The full image is the coordinate reference; region images are higher-detail views of parts of that same screen.\n\nDescribe only what is visibly supported. Identify useful visible UI/game elements with approximate normalized bounding boxes from 0 to 1000. Never invent hidden state, scores, controls, or text. Keep the summary factual and concise.`;

function parseJsonText(text, provider) {
  if (!text) throw new Error(`${provider} returned no JSON text`);
  try { return JSON.parse(text); } catch {
    const fenced = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)?.[1];
    if (fenced) return JSON.parse(fenced);
    throw new Error(`${provider} returned invalid JSON`);
  }
}

function imagesToOpenAIContent(images) { return (Array.isArray(images) ? images : []).map((image) => ({ type: "input_image", image_url: `data:${image.mimeType};base64,${image.data}`, detail: "high" })); }
function imagesToOpenRouterContent(images) { return (Array.isArray(images) ? images : []).map((image) => ({ type: "image_url", image_url: { url: `data:${image.mimeType};base64,${image.data}` } })); }
function imagesToGeminiParts(images) { return (Array.isArray(images) ? images : []).map((image) => ({ inline_data: { mime_type: image.mimeType, data: image.data } })); }

async function postOpenAI(body) {
  const response = await fetch("https://api.openai.com/v1/responses", { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${OPENAI_API_KEY}` }, signal: AbortSignal.timeout(20000), body: JSON.stringify(body) });
  if (!response.ok) { const detail = await response.text(); console.error("OpenAI request failed", response.status, detail.slice(0, 500)); throw new Error(`OpenAI upstream error ${response.status}`); }
  const data = await response.json(); return parseJsonText(data?.output_text, "OpenAI");
}

async function postOpenRouter(prompt, images, schema) {
  const content = [{ type: "text", text: prompt }, ...imagesToOpenRouterContent(images)];
  const response = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${OPENROUTER_API_KEY}`, "HTTP-Referer": "https://gamevision.app", "X-Title": "GameVision" },
    signal: AbortSignal.timeout(20000),
    body: JSON.stringify({ model: OPENROUTER_MODEL, messages: [{ role: "user", content }], response_format: { type: "json_schema", json_schema: { name: "gamevision_json", strict: true, schema } }, temperature: 0.1 })
  });
  if (!response.ok) { const detail = await response.text(); console.error("OpenRouter request failed", response.status, detail.slice(0, 500)); throw new Error(`OpenRouter upstream error ${response.status}`); }
  const data = await response.json();
  const text = data?.choices?.[0]?.message?.content;
  return parseJsonText(text, "OpenRouter");
}

async function postGemini(prompt, images, schema, temperature = 0.1) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`;
  const response = await fetch(`${url}?key=${encodeURIComponent(GEMINI_API_KEY)}`, { method: "POST", headers: { "Content-Type": "application/json" }, signal: AbortSignal.timeout(20000), body: JSON.stringify({ contents: [{ parts: [{ text: prompt }, ...imagesToGeminiParts(images)] }], generationConfig: { responseMimeType: "application/json", responseSchema: schema, temperature } }) });
  if (!response.ok) { const detail = await response.text(); console.error("Gemini request failed", response.status, detail.slice(0, 500)); throw new Error(`Gemini upstream error ${response.status}`); }
  const data = await response.json(); const text = data?.candidates?.[0]?.content?.parts?.find((part) => typeof part.text === "string")?.text; return parseJsonText(text, "Gemini");
}

export async function analyzeWithOpenRouter(images) {
  if (!OPENROUTER_API_KEY) throw new Error("OpenRouter is not configured");
  return postOpenRouter(analysisPrompt, images, screenSchema);
}

export async function askWithOpenRouter(images, instruction, history = [], visionFresh = true) {
  if (!OPENROUTER_API_KEY) throw new Error("OpenRouter is not configured");
  return postOpenRouter(buildAssistantPrompt(instruction, history, Array.isArray(images) && images.length > 0, visionFresh), images, assistantSchema);
}

export async function decideWithOpenRouter(images, goal, history = []) {
  if (!OPENROUTER_API_KEY) throw new Error("OpenRouter is not configured");
  return postOpenRouter(buildAutomationPrompt(goal, history), images, actionSchema);
}

export async function analyzeWithOpenAI(images) {
  if (!OPENAI_API_KEY) throw new Error("OpenAI is not configured");
  return postOpenAI({ model: OPENAI_MODEL, store: false, input: [{ role: "user", content: [{ type: "input_text", text: analysisPrompt }, ...imagesToOpenAIContent(images)] }], text: { format: { type: "json_schema", name: "gamevision_screen_analysis", strict: true, schema: screenSchema } } });
}

export async function analyzeWithGemini(images) {
  if (!GEMINI_API_KEY) throw new Error("Gemini is not configured");
  return postGemini(analysisPrompt, images, { type: "OBJECT", properties: { summary: { type: "STRING" }, state: { type: "STRING" }, confidence: { type: "NUMBER" }, elements: { type: "ARRAY", items: { type: "OBJECT", properties: { label: { type: "STRING" }, x: { type: "NUMBER" }, y: { type: "NUMBER" }, width: { type: "NUMBER" }, height: { type: "NUMBER" }, confidence: { type: "NUMBER" } }, required: ["label", "x", "y", "width", "height", "confidence"] } }, notes: { type: "ARRAY", items: { type: "STRING" } } }, required: ["summary", "state", "confidence", "elements", "notes"] });
}

export async function askWithOpenAI(images, instruction, history = [], visionFresh = true) {
  if (!OPENAI_API_KEY) throw new Error("OpenAI is not configured");
  return postOpenAI({ model: OPENAI_MODEL, store: false, input: [{ role: "user", content: [{ type: "input_text", text: buildAssistantPrompt(instruction, history, Array.isArray(images) && images.length > 0, visionFresh) }, ...imagesToOpenAIContent(images)] }], text: { format: { type: "json_schema", name: "gamevision_assistant_reply", strict: true, schema: assistantSchema } } });
}

export async function askWithGemini(images, instruction, history = [], visionFresh = true) {
  if (!GEMINI_API_KEY) throw new Error("Gemini is not configured");
  return postGemini(buildAssistantPrompt(instruction, history, Array.isArray(images) && images.length > 0, visionFresh), images, { type: "OBJECT", properties: { answer: { type: "STRING" }, confidence: { type: "NUMBER" } }, required: ["answer", "confidence"] });
}

export async function decideWithOpenAI(images, goal, history = []) {
  if (!OPENAI_API_KEY) throw new Error("OpenAI is not configured");
  return postOpenAI({ model: OPENAI_MODEL, store: false, input: [{ role: "user", content: [{ type: "input_text", text: buildAutomationPrompt(goal, history) }, ...imagesToOpenAIContent(images)] }], text: { format: { type: "json_schema", name: "gamevision_action_plan", strict: true, schema: actionSchema } } });
}

export async function decideWithGemini(images, goal, history = []) {
  if (!GEMINI_API_KEY) throw new Error("Gemini is not configured");
  return postGemini(buildAutomationPrompt(goal, history), images, { type: "OBJECT", properties: { type: { type: "STRING", enum: ACTION_TYPES }, x: { type: "NUMBER" }, y: { type: "NUMBER" }, x2: { type: "NUMBER" }, y2: { type: "NUMBER" }, text: { type: "STRING" }, durationMs: { type: "NUMBER" }, waitMs: { type: "NUMBER" }, reason: { type: "STRING" }, confidence: { type: "NUMBER" }, verify: { type: "BOOLEAN" }, stopReason: { type: "STRING" } }, required: ["type", "x", "y", "x2", "y2", "text", "durationMs", "waitMs", "reason", "confidence", "verify", "stopReason"] });
}
