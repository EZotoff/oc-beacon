# 10h 人类行为模拟（高度引擎 soak）

**专用文件夹**（用户指定）。2026-10-04 建。

## 目的
- 高度引擎/流式渲染栈的长时间随机化人类行为 soak（10 小时挂钟）
- 配合静态覆盖审计（见仓库 journal）验证 #509/#510 后的全部分支/竞态面

## 架构（零真实配额消耗）
```
driver.py（宿主, setsid 存活）
   │ adb（keyevent 打字禁 input text / 动态 dump 定位 / 安全黑名单）
   ▼
真机 e69a99d8 · dev 包 · debug intent → 127.0.0.1:4299（adb reverse）
   ▼
v1-e2e 容器（opencode 1.18.32, --network host, mockmd provider）
   ▼ 127.0.0.1:4290
human-sim-mockllm 容器（server.js：12 类高度压力语料 × 7 速率画像 + 5% abort-mid）
```
- mock 语料：宽表/长代码/嵌套列表/mixed(数学+坏图)/长段落/短轮/中文表/40 标题/清单引用/4k 单段/逐行长表(塌缩诱饵)/8KB 组合
- 速率画像：fast/normal/slow/bursty/stall-mid(3-5s 停顿打 RESERVE)/line/megachunk(150-400字大块) + abort-mid(断流打中断轮)
- 原 zhipuai 配置备份于 `~/v1-e2e/config/opencode/opencode.jsonc.bak-zhipuai-20261004`

## 踩坑记录（复现必读）
1. **SSE `data: ` 前缀**：mock 首版漏前缀 → AI SDK 零事件 → opencode 空消息无限重试（11 连发）。SDK 报
   `AI_InvalidResponseDataError: Response stream ended without a finish reason`，宿主侧 `node fetch` 肉眼看线
   才定位。已修。
2. **IME 弹出后发送键位移**（~870px）：一律 dump 后动态定位（记忆已有此坑，重踩一次）。
3. **pkill 自匹配**：清理命令的 pattern 出现在自己命令行 → 自杀。用 `for p in $(pgrep -f ...); [ $p != $$ ]`。
4. **MIUI logcat 缓冲刷穿**：探针判定必须连续流式捕获到文件（cap_*.log 轮转），禁 post-hoc `logcat -d`。

## 文件
- `mockllm/server.js` — mock LLM（OpenAI 兼容流式）
- `driver/driver.py` — 行为驱动器（人类模型/护栏/监控恢复/轮转捕获）
- `driver/run.sh` — 脱离启动 · `driver/review.sh` — cron 巡检（签名台账 + 死亡复活）
- `logs/cap_*.log` — 轮转 logcat · `evidence/` — 截图/崩溃 · `reports/` — 台账/进度/actions.jsonl

## 判读签名（#484 记忆集，两种负向 d 都扫）
负向 `MDResize card=.. h=.. d=-`、`RESIZE .. h a->b (d=-`、RESETKEY、GUARD reanchor、nonPrefix、
RESERVE flush reserved=、ItemP、[swap]、HFLICK 同 n add1 rem1。健康基线：4h 重度零负向 d 零 RESETKEY。
