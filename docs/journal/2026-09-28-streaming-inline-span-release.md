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

## #472 验收轮回归:完结闪烁清空根修(2026-09-28 20:03)

- 现象(用户主诉,严重):流式输出到一半屏幕闪烁——内容像被清空,~1.5s 后恢复到原进度。
- 定罪(film 轮日志):20:03:57.177 完结瞬间服务端连发 MessagePartUpdated+TextStarted+4×sync(完结 part 重组)→同一渲染槽 markdown 非前缀跳变→pilot 单发非前缀静默重建(resetKey++,仅风暴才打日志)→旧树 dispose 清屏→首跑铺开按 #438① 限速 200ch/200ms 回灌 1797ch≈1.5s(append 200ch×5 签名)。
- 回归源:旧 hold 语义 ever&&!ready 对 ≤2048(无异步终态,ready 恒 false)pilot 永不退场→完结重组砸进 pilot。修复前 ≤2048 完结立即切同步解析路径,重组碰不到 pilot;#438① 限速把回灌从单帧变成可见铺开,放大为可感知闪烁。
- 修复(双层,任一独立足防):(A)hold 收窄为异步终态在途(pilotTerminalHold(ever, asyncPending),>2048 Loading 桥接语义不变,≤2048 完结即退场);(B)非前缀 300ms 宽限冻结(nonPrefixRebuildDue 纯函数,摆动旧串回来无缝续播,超窗真重生成才重建)+hold 桥接期 pilot 输入整冻结(freeze 参数,终帧即终点)。
- 测试:pilotTerminalHold 语义更新+noTerminal 释放锚+宽限 3 例;markdown 域全绿→全量单测绿(20:14)。
- 真机验证(jazz 轮,20:16):73 批放行、append 200ch=0(零回灌)、releasedTotal 严格单调→1693、MD472 流中 hold=false(新语义)/完结新实例(ever=false,len=253)直接走完结路径——用户确认「正常结束了」。
- 边界:上游「服务端完结 part 重组/sync」与「数据层 reconciler 双写摆动」(FlapDetector 注释登记的另立卡)不在本次范围——渲染层已结构免疫。

## 已完结卡片迁入（2026-09-28）

### **#472 流式markdown已闭合行内构造即时放行——含标记段落不再憋到段落末一口气** `sse` `render` `streaming`
  - 用户主诉:斜体等标记行整段扣留到空行毕业才一次性放行(一口气吐出超多内容,斜体最显眼)。根因:SafePrefixGate 行扫描对含 ACTIVE_MARKERS 的行整行 break 扣留,已闭合构造也被扣到段落末。方案:inlineSafeCut 侧翼感知扫描器(与 markdown-jvm 0.7.9 的 canOpen/canClose 判定逐字一致),已闭合 emphasis/code/strikethrough+纯文字尾巴按纯文字同节奏增量放行;未闭合仍零输出(2026-09-25 用户裁决);[]!|#|>|$$|☐☑✅ 硬停维持现状。backlog:322(#441 后续行级定案指示)的落地。
  - 注:本卡编号 472 与历史 commit 措辞「#472 完结换装(pilotTerminalHold)」无关联——该子项挂 #471② 从未占卡号。
  - 迁入依据：用户验收通过(2026-09-28):斜体流式逐字直出+完结零闪烁两关体感确认;jazz轮73批零回灌单调收尾仪器佐证（backlog.sh migrate 2026-09-28）
