// mockllm/server.js — 10h 人类模拟专用 mock LLM（OpenAI chat-completions 兼容）
//
// 目标：给 v1-e2e（opencode 1.18.32, --network host, VM loopback:4299）提供
// 零成本、完全可控的流式 markdown 源。真实 OpenCode 事件管线原样驱动 app，
// 内容与速率在本层塑形——高度引擎 soak 的核心变量都在这里。
//
// 部署：docker run --network host（与 v1-e2e 同 netns）→ baseURL
//   http://127.0.0.1:4290/v1
// 日志：stdout（docker logs），每次调用一行访问记录，供 10h 台账对时。
//
// 速率画像（每次调用随机）：fast/normal/slow/bursty/stall-mid/line/megachunk
//   + abort-mid（5% 概率：60% 处断流——打中断轮竞态面）
// 语料（全局轮转）：12 类高度压力文档，见 CORPORA。

const http = require("http");
const PORT = 4290;

// ---------- 语料（数组行拼接，避免反引号冲突） ----------
const CORPORA = [];
{
  // 1. wide-table：22 行 6 列长单元格表（行内换行 → 测量方差大）
  const rows = [];
  for (let i = 1; i <= 22; i++) {
    rows.push(
      "| module-" + i + " (category " + ((i % 5) + 1) + ") | " +
      "streaming coordinator entry with incremental prefix diff, " + i * 3 + " tokens | " +
      "measured " + (120 + i * 17) + "px baseline, reserve " + (i % 4) + " | " +
      (i % 2 ? "paired" : "unpaired") + " | " + "0x" + (0x1a00 + i).toString(16) + " | " +
      "stable across " + i + " recompositions |"
    );
  }
  CORPORA.push(["## Wide table stress", "",
    "| name | behavior | height | state | id | note |", "|---|---|---|---|---|---|",
    ...rows, "", "Table ends with a trailing paragraph to anchor the block below it."].join("\n"));

  // 2. code-long：200 行长行 Kotlin
  const code = [];
  for (let i = 0; i < 200; i++) {
    code.push("fun handler$i(x: Int): String = compute$i(x) + suffix-${'$'}{x * " + i + "} // line " + i +
      " with trailing comment long enough to wrap on narrow screens");
  }
  CORPORA.push(["## Long code block", "", "```kotlin", ...code, "```", "",
    "After the fence, an explanation paragraph with `inline` code and **bold** claims about " +
    "measurement stability during streaming graduation."].join("\n"));

  // 3. nested-lists：4 层嵌套 + 复选框 + 行内样式
  const items = [];
  for (let i = 0; i < 30; i++) {
    items.push("  ".repeat(i % 4) + "- [ ] item " + i + " with *emphasis*, `code` and [link](#a" + i + ")");
    if (i % 3 === 0) items.push("  ".repeat((i % 4) + 1) + "- [x] nested done " + i);
  }
  CORPORA.push(["## Nested checklist", "", ...items].join("\n"));

  // 4. mixed-stress：标题梯 + 引用嵌套 + hr + 链接 + 坏图 + 数学围栏
  CORPORA.push([
    "# Top", "## Second", "### Third", "#### Fourth",
    "> quote level 1", ">> quote level 2", ">>> quote level 3",
    "", "---", "",
    "Inline math $E = mc^2$ and a display block:", "",
    "$$", "\\int_0^1 f(x)\\,dx = \\sum_{i=0}^{n} w_i f(x_i)", "$$", "",
    "[external link](https://example.invalid/never-resolves) plus a broken image:",
    "", "![broken image](https://example.invalid/pic.png)", "",
    "Both exercise placeholder / error-height paths when offline."
  ].join("\n"));

  // 5. long-paragraphs：8 段长纯文本（无结构 → 行测量主导）
  const paras = [];
  for (let p = 0; p < 8; p++) {
    let s = "Paragraph " + p + ": ";
    for (let w = 0; w < 90; w++) s += "word" + (p * 90 + w) + " ";
    paras.push(s.trim());
  }
  CORPORA.push(paras.join("\n\n"));

  // 6. short-reply：短轮（低于 async 阈值——T2-a 短轮竞态面）
  CORPORA.push("ok. done.");

  // 7. cjk-table：中文表头 12 行 + 中文长段
  {
    const t = ["## 中文表格", "", "| 模块 | 行为 | 高度 | 状态 |", "|---|---|---|---|"];
    for (let i = 1; i <= 12; i++) t.push("| 模块" + i + " | 流式增量前缀差分，" + i * 7 + " 词 | 基线 " + (200 + i * 23) + "px | " + (i % 2 ? "已配对" : "未配对") + " |");
    t.push("", "表格之后是一段中文长文本，用来验证行高测量在 CJK 字符集下的稳定性，".repeat(6));
    CORPORA.push(t.join("\n"));
  }

  // 8. headings-only：40 个标题（廉价重排压力）
  {
    const h = [];
    for (let i = 0; i < 40; i++) h.push("#".repeat((i % 5) + 1) + " Heading level " + (i % 5 + 1) + " index " + i);
    CORPORA.push(h.join("\n\n"));
  }

  // 9. checklist+quote mix
  {
    const m = ["### Mixed", ""];
    for (let i = 0; i < 25; i++) m.push("- [ ] task " + i, "  > note for task " + i + ": verify before close");
    CORPORA.push(m.join("\n"));
  }

  // 10. giant-single：4000 字符少换行段落（宽度溢出/nowrap 压力）
  {
    let s = "";
    while (s.length < 4000) s += "token" + s.length + " ";
    CORPORA.push("## Single blob\n\n" + s.trim());
  }

  // 11. progressive-table：文字引导 + 逐行长表（#470/#484 塌缩诱饵形态）
  {
    const t = ["下面表格会一行一行长出来，是历史上塌缩问题的高发形态。", "",
      "| step | op | delta | verdict |", "|---|---|---|---|"];
    for (let i = 1; i <= 30; i++) t.push("| s" + i + " | append row " + i + " | +" + (i * 13) + " | ok |");
    CORPORA.push(t.join("\n"));
  }

  // 12. multi-section doc：~8KB 组合文档（跨分片/发布阈值）
  {
    const d = ["# Big document", ""];
    for (let s = 1; s <= 6; s++) {
      d.push("## Section " + s, "");
      for (let p = 0; p < 4; p++) {
        let para = "Section " + s + " paragraph " + p + ": ";
        for (let w = 0; w < 70; w++) para += "s" + s + "p" + p + "w" + w + " ";
        d.push(para.trim(), "");
      }
      if (s % 2 === 0) {
        d.push("| k | v |", "|---|---|");
        for (let r = 0; r < 8; r++) d.push("| key-" + s + "-" + r + " | value cell " + r * 31 + " px |");
        d.push("");
      }
      if (s % 3 === 0) d.push("```python", "def f" + s + "(x):", "    return x * " + s, "```", "");
    }
    CORPORA.push(d.join("\n"));
  }
}

// 语料名（演示控制面 [c:<名>] 用；顺序与 CORPORA 一一对应）
const CORPUS_NAMES = ["wide-table", "code-long", "nested-lists", "mixed-stress", "long-paragraphs",
  "short-reply", "cjk-table", "headings-only", "checklist-quote", "giant-single", "progressive-table", "multi-section"];
const PROFILES = ["fast", "normal", "slow", "bursty", "stall-mid", "line", "megachunk", "abort-mid"];

// ---------- 速率画像 ----------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let counter = 0;

function pickProfile(rng) {
  if (rng() < 0.05) return "abort-mid";
  const pool = ["fast", "normal", "normal", "slow", "bursty", "stall-mid", "line", "megachunk"];
  return pool[Math.floor(rng() * pool.length)];
}
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// 把整篇文本切成 [chunk, delayAfterMs] 序列
function planChunks(text, profile, rng) {
  const out = [];
  const n = text.length;
  let i = 0;
  let stallAt = n * (0.4 + rng() * 0.2), stalled = false;
  let abortAt = n * 0.6, aborting = profile === "abort-mid";
  while (i < n) {
    let size, delay;
    switch (profile) {
      case "fast": size = 4 + Math.floor(rng() * 12); delay = 8 + rng() * 18; break;
      case "normal": size = 2 + Math.floor(rng() * 7); delay = 30 + rng() * 60; break;
      case "slow": size = 2 + Math.floor(rng() * 3); delay = 60 + rng() * 90; break;
      case "bursty": size = 3 + Math.floor(rng() * 10); delay = rng() < 0.2 ? 500 + rng() * 400 : 10; break;
      case "stall-mid": size = 2 + Math.floor(rng() * 6); delay = 40 + rng() * 50; break;
      case "line": {
        const nl = text.indexOf("\n", i);
        size = (nl === -1 ? n : nl + 1) - i; delay = 60 + rng() * 140; break;
      }
      case "megachunk": size = 150 + Math.floor(rng() * 250); delay = 400 + rng() * 500; break;
      case "abort-mid": size = 2 + Math.floor(rng() * 8); delay = 30 + rng() * 70; break;
      default: size = 5; delay = 40;
    }
    const end = Math.min(i + Math.max(1, size), n);
    out.push([text.slice(i, end), Math.round(delay)]);
    i = end;
    if (profile === "stall-mid" && !stalled && i >= stallAt) {
      stalled = true;
      out.push(["", 3000 + Math.round(rng() * 2000)]); // RESERVE flush 触发窗
    }
    if (aborting && i >= abortAt) { out.push([null, 0]); break; } // null = 断流
  }
  return out;
}

const server = http.createServer((req, res) => {
  const started = Date.now();
  if (req.method === "GET" && (req.url === "/v1/models" || req.url === "/models")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ object: "list", data: [{ id: "mock-md", object: "model", owned_by: "mockllm" }] }));
    return;
  }
  if (req.method === "GET" && req.url === "/healthz") {
    res.writeHead(200); res.end("ok"); return;
  }
  if (req.method !== "POST" || !/chat\/completions$/.test(req.url)) {
    res.writeHead(404); res.end(); return;
  }
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", async () => {
    let parsed = {};
    try { parsed = JSON.parse(body); } catch (e) {}
    if (process.env.WIRE) {
      try { require("fs").writeFileSync("/tmp/req-last.json", req.method + " " + req.url + "\nHEADERS:" + JSON.stringify(req.headers) + "\nBODY:" + body); } catch (e) {}
    }
    const idx = counter++;
    const rng = mulberry32((started & 0xffff) * 7919 + idx * 104729);
    const messages = parsed.messages || [];
    // title/摘要类调用（消息极短且含 title 关键词）→ 短非流式答复
    const flat = messages.map((m) => (m.content || "")).join(" ").toLowerCase();
    const isTitle = flat.includes("title") && flat.length < 400;
    const isSdkProbe = flat.includes("sdk probe");
    // 演示控制面（2026-10-05 桶A验收）：消息含 [c:<语料名>] / [p:<画像名>] 时
    // 定向出稿；无指令行为不变（轮转语料+随机画像）——soak 复跑语义保持
    let corpusIdx = idx % CORPORA.length;
    let profile = isSdkProbe || isTitle ? "fast" : pickProfile(rng);
    if (!isSdkProbe && !isTitle) {
      const mc = flat.match(/\[c:([a-z0-9-]+)\]/);
      if (mc) { const k = CORPUS_NAMES.indexOf(mc[1]); if (k >= 0) corpusIdx = k; }
      const mp = flat.match(/\[p:([a-z-]+)\]/);
      if (mp && PROFILES.indexOf(mp[1]) >= 0) profile = mp[1];
    }
    const text = isSdkProbe ? "hello from mock sdk probe one two three" : (isTitle ? "Soak turn " + idx : CORPORA[corpusIdx]);
    const wantsStream = parsed.stream !== false;

    const chunkJson = (delta, finish) => JSON.stringify({
      id: "chatcmpl-mock-" + idx, object: "chat.completion.chunk", created: Math.floor(started / 1000),
      model: "mock-md",
      choices: [{ index: 0, delta, finish_reason: finish ?? null }],
    });

    if (!wantsStream) {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        id: "chatcmpl-mock-" + idx, object: "chat.completion", created: Math.floor(started / 1000),
        model: "mock-md",
        choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }],
        usage: { prompt_tokens: 10, completion_tokens: Math.ceil(text.length / 4), total_tokens: 10 + Math.ceil(text.length / 4) },
      }));
      log(idx, corpusIdx, profile, "nostream", text.length, 1, Date.now() - started, false);
      return;
    }

    res.writeHead(200, {
      "Content-Type": "text/event-stream", "Cache-Control": "no-cache",
      Connection: "keep-alive", "X-Accel-Buffering": "no",
    });
    res.flushHeaders();
    const plan = isTitle ? [[text, 30]] : planChunks(text, profile, rng);
    let sent = 0, nchunks = 0, aborted = false;
    for (const [piece, delay] of plan) {
      if (piece === null) { aborted = true; break; } // 中途断流
      await sleep(delay);
      if (res.destroyed) break;
      res.write("data: " + chunkJson({ content: piece }, null) + "\n\n");
      sent += piece.length; nchunks++;
    }
    if (!aborted && !res.destroyed) {
      res.write("data: " + chunkJson({}, "stop") + "\n\n");
      res.write("data: " + JSON.stringify({
        id: "chatcmpl-mock-fin", object: "chat.completion.chunk", created: Math.floor(started / 1000),
        model: "mock-md", choices: [],
        usage: { prompt_tokens: 10, completion_tokens: Math.ceil(sent / 4), total_tokens: 10 + Math.ceil(sent / 4) },
      }) + "\n\n");
      res.write("data: [DONE]\n\n");
    }
    res.end();
    log(idx, corpusIdx, profile, wantsStream ? "stream" : "nostream", sent, nchunks, Date.now() - started, aborted);
  });
});

function log(idx, corpus, profile, mode, bytes, chunks, durMs, aborted) {
  const ts = new Date().toISOString();
  console.log(ts + " call=" + idx + " corpus=" + corpus + " profile=" + profile +
    " mode=" + mode + " bytes=" + bytes + " chunks=" + chunks + " durMs=" + durMs +
    (aborted ? " ABORTED=true" : ""));
}

server.listen(PORT, "127.0.0.1", () => console.log(new Date().toISOString() + " mockllm listening on 127.0.0.1:" + PORT + " corpora=" + CORPORA.length));
