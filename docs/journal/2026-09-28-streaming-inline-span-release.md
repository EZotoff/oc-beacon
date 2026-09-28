# streaming inline span release（2026-09-28）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## #472 实现+验证（2026-09-28）

- 定罪：SafePrefixGate 行扫描对含 ACTIVE_MARKERS 的行整行 else->break 扣留，已闭合 *斜体*/**粗体**/`代码` 也扣到空行毕业/EOF；扣留期超龄揭示通道已被 2026-09-25 裁决关闭 → 静默后整段倾泻。历史仪器证据：R9 444ms 聚 7 批 d=6236px；aged reveal 1091ch→7378px；run4 append max 2087ch。
- 实现：InlineSpanSafety.safeCut 纯函数，判定逐字镜像 markdown-jvm 0.7.9（DelimiterParser.canOpenClose：*/~ canSplitText=true、_ 不可词内；EmphasisLikeParser.balanceDelimiters：look-back 近者优先+rule of 3+openers_bottom，run 粒度改写；BacktickParser 顺序等长闭符；字符分类镜像 CommonDefsImplJvm）。保守扣留：未闭合开标记/硬停字符(\ [ ] ! | # > $$ ☐☑✅)前纯文字放行/快照尾 run（EOF 侧翼未知+防跨批 run 撕裂）/配对 span 跨扣留点开标记（修正环）。
- 接线：else 分支行内增量放行；列表/引用/ATX 行加行内安全帽（顺带修复列表行悬空 opener 先放后重排既有缺口）；ACTIVE_MARKERS 增补反斜杠。语义与「未闭合构造零输出」裁决一致——实现从「闭合也扣到段落末」收窄回裁决本意；backlog:322 指示落地。
- 单测：InlineSpanSafetyTest 30 例（新）+ SafePrefixGateTest 8 断言更新/3 新例 + BlockGranularity 保绿；全量 testDevDebugUnitTest 绿（19:47/19:49）。
- 真机（houji TCP，19:52 英文提示词诱导斜体/粗体）：主流 100+ 批 release=append 同批 5-57ch，held 除两处瞬态（4ch/2ch 下批毕业）恒 0——段落末无倾泻；MD472 hold 链完好。截图本地 docs/journal/assets/2026-09-28-472-italic-stream-verify.png（png 被 ignore，不入库）。
- 遗留：MD472 探针待用户验收后统一清理（用户裁决：保留辅助本轮验收）。
