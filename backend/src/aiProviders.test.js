import test from "node:test";
import assert from "node:assert/strict";
import { classifyOpenRouterFailure, isUsableOpenRouterKey } from "./aiProviders.js";

test("rejects missing OpenRouter keys", () => {
  assert.equal(isUsableOpenRouterKey(""), false);
  assert.equal(isUsableOpenRouterKey("   "), false);
});

test("rejects unresolved environment placeholders", () => {
  assert.equal(isUsableOpenRouterKey("${OPENROUTER_API_KEY}"), false);
  assert.equal(isUsableOpenRouterKey("$OPENROUTER_API_KEY"), false);
  assert.equal(isUsableOpenRouterKey("REPLACE_WITH_OPENROUTER_API_KEY"), false);
});

test("accepts a non-placeholder OpenRouter key", () => {
  assert.equal(isUsableOpenRouterKey("sk-or-v1-example-key-value"), true);
});

test("classifies common OpenRouter failures into safe actionable states", () => {
  assert.deepEqual(classifyOpenRouterFailure(401), {
    code: "OPENROUTER_AUTH_FAILED",
    retryable: false,
    detail: "OpenRouter rejected the server key. Check the OPENROUTER_API_KEY in Render."
  });
  assert.equal(classifyOpenRouterFailure(429).code, "FREE_AI_RATE_LIMITED");
  assert.equal(classifyOpenRouterFailure(504).code, "FREE_AI_UPSTREAM_ERROR");
  assert.equal(classifyOpenRouterFailure(408).code, "OPENROUTER_TIMEOUT");
});

test("does not echo provider response bodies as the public error detail", () => {
  const result = classifyOpenRouterFailure(500, "Authorization: Bearer sk-or-v1-secret-value");
  assert.equal(result.code, "FREE_AI_UPSTREAM_ERROR");
  assert.equal(result.detail.includes("sk-or-v1-secret"), false);
});
