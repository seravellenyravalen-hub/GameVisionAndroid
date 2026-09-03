const OPENAI_API_KEY = process.env.OPENAI_API_KEY;
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-5.6-sol";
const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-3.8-flash";

export const providerStatus = {
  openaiConfigured: Boolean(OPENAI_API_KEY),
  openaiModel: OPENAI_MODEL,
  geminiConfigured: Boolean(GEMINI_API_KEY),
  geminiModel: GEMINI_MODEL
};

const providerSchema = {
  type: "object",
  properties: {
    homeTeam: { type: ["string", "null"] },
    awayTeam: { type: ["string", "null"] },
    homeScore: { type: ["integer", "null"] },
    awayScore: { type: ["integer", "null"] },
    minute: { type: ["string", "null"] },
    event: { type: ["string", "null"] },
    confidence: { type: "number" },
    notes: { type: "array", items: { type: "string" } }
  },
  required: ["homeTeam", "awayTeam", "homeScore", "awayScore", "minute", "event", "confidence", "notes"],
  additionalProperties: false
};

export const analysisPrompt = `You are GameVision, a conservative sports-screen vision analyzer. Analyze ONLY the visible pixels in this screenshot/frame. Do not use hidden data, APIs, memory, assumptions, or outside knowledge. Identify a live sports scoreboard or game HUD only when it is clearly visible.

Rules:
- If a team name, score, minute, or event is not clearly readable, return null for that field.
- Never invent or guess a score.
- confidence is 0 to 100 and must reflect visual certainty only.
- notes must be short and factual.
- Return only JSON matching the supplied schema.`;

function parseJsonText(text, provider) {
  if (!text) throw new Error(`${provider} returned no analysis text`);
  try {
    return JSON.parse(text);
  } catch {
    const fenced = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)?.[1];
    if (fenced) return JSON.parse(fenced);
    throw new Error(`${provider} returned invalid JSON`);
  }
}

export async function analyzeWithOpenAI(image) {
  if (!OPENAI_API_KEY) throw new Error("OpenAI is not configured");

  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${OPENAI_API_KEY}`
    },
    signal: AbortSignal.timeout(15000),
    body: JSON.stringify({
      model: OPENAI_MODEL,
      store: false,
      input: [{
        role: "user",
        content: [
          { type: "input_text", text: analysisPrompt },
          { type: "input_image", image_url: `data:${image.mimeType};base64,${image.data}`, detail: "high" }
        ]
      }],
      text: {
        format: {
          type: "json_schema",
          name: "gamevision_frame_analysis",
          strict: true,
          schema: providerSchema
        }
      }
    })
  });

  if (!response.ok) {
    const detail = await response.text();
    console.error("OpenAI request failed", response.status, detail.slice(0, 500));
    throw new Error(`OpenAI upstream error ${response.status}`);
  }

  const data = await response.json();
  return parseJsonText(data?.output_text, "OpenAI");
}

export async function analyzeWithGemini(image) {
  if (!GEMINI_API_KEY) throw new Error("Gemini is not configured");

  const url = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`;
  const response = await fetch(`${url}?key=${encodeURIComponent(GEMINI_API_KEY)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    signal: AbortSignal.timeout(15000),
    body: JSON.stringify({
      contents: [{
        parts: [
          { text: analysisPrompt },
          { inline_data: { mime_type: image.mimeType, data: image.data } }
        ]
      }],
      generationConfig: {
        responseMimeType: "application/json",
        responseSchema: {
          type: "OBJECT",
          properties: {
            homeTeam: { type: "STRING", nullable: true },
            awayTeam: { type: "STRING", nullable: true },
            homeScore: { type: "INTEGER", nullable: true },
            awayScore: { type: "INTEGER", nullable: true },
            minute: { type: "STRING", nullable: true },
            event: { type: "STRING", nullable: true },
            confidence: { type: "NUMBER" },
            notes: { type: "ARRAY", items: { type: "STRING" } }
          },
          required: ["homeTeam", "awayTeam", "homeScore", "awayScore", "minute", "event", "confidence", "notes"]
        },
        temperature: 0
      }
    })
  });

  if (!response.ok) {
    const detail = await response.text();
    console.error("Gemini request failed", response.status, detail.slice(0, 500));
    throw new Error(`Gemini upstream error ${response.status}`);
  }

  const data = await response.json();
  const text = data?.candidates?.[0]?.content?.parts?.find((part) => typeof part.text === "string")?.text;
  return parseJsonText(text, "Gemini");
}
