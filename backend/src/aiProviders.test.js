import test from "node:test";
import assert from "node:assert/strict";
import { isUsableOpenRouterKey } from "./aiProviders.js";

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
