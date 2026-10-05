// test-sdk.js — 用 @ai-sdk/openai-compatible 直接消费 mock，复现 opencode 拿到 0 文本的问题
const { createOpenAICompatible } = require("@ai-sdk/openai-compatible");
const { streamText } = require("ai");

const provider = createOpenAICompatible({
  name: "mockmd",
  baseURL: "http://127.0.0.1:4290/v1",
  apiKey: "mock",
});

(async () => {
  try {
    const r = streamText({
      model: provider("mock-md"),
      prompt: "sdk probe",
    });
    let text = "";
    for await (const part of r.fullStream) {
      if (part.type === "text-delta") text += part.textDelta;
    }
    const usage = await r.usage;
    console.log("SDK-RESULT textLen=" + text.length + " finish=" + await r.finishReason +
      " usage=" + JSON.stringify(usage));
    console.log("SDK-SAMPLE " + text.slice(0, 80).replace(/\n/g, "|"));
  } catch (e) {
    console.log("SDK-ERROR " + e.stack);
  }
})();
