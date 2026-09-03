import express from "express";

const app = express();
const PORT = process.env.PORT || 3000;
const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash-lite";
const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`;

app.use(express.json({ limit: "10mb" }));

app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept, User-Agent");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

app.get("/health", (req, res) => {
  res.status(200).json({
    status: "healthy",
    service: "gamevision-api",
    aiConfigured: Boolean(GEMINI_API_KEY),
    model: GEMINI_MODEL,
    timestamp: new Date().toISOString()
  });
});

app.get("/", (req, res) => {
  res.json({
    service: "GameVision API",
    status: "online",
    aiConfigured: Boolean(GEMINI_API_KEY),
    model: GEMINI_MODEL
  });
});

const responseSchema = {
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
};

function cleanText(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function parseGeminiJson(data) {
  const text = data?.candidates?.[0]?.content?.parts?.find((part) => typeof part.text === "string")?.text;
  if (!text) throw new Error("Gemini returned no analysis text");
  return JSON.parse(text);
}

app.post("/api/analyze-frame", async (req, res, next) => {
  try {
    const image = req.body?.image;

    if (!image?.data || !image?.mimeType) {
      return res.status(400).json({ error: "Image payload required" });
    }

    if (!GEMINI_API_KEY) {
      return res.status(503).json({
        error: "AI analysis is not configured",
        code: "AI_NOT_CONFIGURED"
      });
    }

    if (!["image/jpeg", "image/png", "image/webp"].includes(image.mimeType)) {
      return res.status(400).json({ error: "Unsupported image type" });
    }

    const prompt = `You are GameVision, a conservative sports-screen vision analyzer. Analyze ONLY the visible pixels in this screenshot/frame. Do not use hidden data, APIs, memory, assumptions, or outside knowledge. Identify a live sports scoreboard or game HUD only when it is clearly visible.

Rules:
- If a team name, score, minute, or event is not clearly readable, return null for that field.
- Never invent or guess a score.
- confidence is 0 to 100 and must reflect visual certainty only.
- notes must be short and factual.
- Return JSON matching the supplied schema.`;

    const response = await fetch(`${GEMINI_URL}?key=${encodeURIComponent(GEMINI_API_KEY)}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      signal: AbortSignal.timeout(15000),
      body: JSON.stringify({
        contents: [{
          parts: [
            { text: prompt },
            { inline_data: { mime_type: image.mimeType, data: image.data } }
          ]
        }],
        generationConfig: {
          responseMimeType: "application/json",
          responseSchema,
          temperature: 0
        }
      })
    });

    if (!response.ok) {
      const detail = await response.text();
      console.error("Gemini request failed", response.status, detail.slice(0, 500));
      return res.status(502).json({
        error: "AI analysis service unavailable",
        code: "AI_UPSTREAM_ERROR"
      });
    }

    const data = await response.json();
    const result = parseGeminiJson(data);

    const homeScore = Number.isInteger(result.homeScore) && result.homeScore >= 0 ? result.homeScore : null;
    const awayScore = Number.isInteger(result.awayScore) && result.awayScore >= 0 ? result.awayScore : null;
    const confidence = clamp(Number(result.confidence) || 0, 0, 100);
    const verified = homeScore !== null && awayScore !== null && Boolean(cleanText(result.minute)) && confidence >= 80;

    const score = homeScore !== null && awayScore !== null ? `${homeScore}-${awayScore}` : "Unknown";
    const risk = verified ? "low" : "review";
    const notes = Array.isArray(result.notes) ? result.notes.filter((item) => typeof item === "string").slice(0, 5) : [];

    return res.json({
      analysis: {
        score,
        confidence: Math.round(confidence),
        verified,
        risk,
        notes: notes.length ? notes : ["Analysis based only on visible frame content."],
        prediction: { home: 0, draw: 0, away: 0 },
        details: {
          homeTeam: cleanText(result.homeTeam),
          awayTeam: cleanText(result.awayTeam),
          minute: cleanText(result.minute),
          event: cleanText(result.event)
        }
      }
    });
  } catch (error) {
    console.error("GameVision frame analysis error:", error?.message || error);
    return res.status(502).json({
      error: "Unable to analyze frame",
      code: "AI_ANALYSIS_FAILED"
    });
  }
});

app.use((err, req, res, next) => {
  console.error("GameVision API error:", err);
  res.status(500).json({ error: "Internal server error" });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`GameVision API listening on port ${PORT}`);
});
