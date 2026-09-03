import express from "express";

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: "10mb" }));

app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept, User-Agent");
  if (req.method === "OPTIONS") {
    return res.sendStatus(204);
  }
  next();
});

app.get("/health", (req, res) => {
  res.status(200).json({
    status: "healthy",
    service: "gamevision-api",
    timestamp: new Date().toISOString()
  });
});

app.get("/", (req, res) => {
  res.json({
    service: "GameVision API",
    status: "online"
  });
});

app.post("/api/analyze-frame", (req, res) => {
  const image = req.body?.image;

  if (!image?.data || !image?.mimeType) {
    return res.status(400).json({
      error: "Image payload required"
    });
  }

  console.log("GameVision frame received", {
    mimeType: image.mimeType,
    base64Length: image.data.length
  });

  res.json({
    analysis: {
      score: "0-0",
      confidence: 0,
      verified: false,
      risk: "review",
      notes: ["Frame received successfully. AI analysis is not configured yet."],
      prediction: {
        home: 50,
        draw: 25,
        away: 25
      }
    }
  });
});

app.use((err, req, res, next) => {
  console.error("GameVision API error:", err);
  res.status(500).json({
    error: "Internal server error"
  });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`GameVision API listening on port ${PORT}`);
});
