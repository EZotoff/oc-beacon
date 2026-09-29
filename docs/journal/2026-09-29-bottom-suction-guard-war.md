# bottom-suction-guard-war（2026-09-29）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 根因取证与修复(#476,2026-09-29)

- **用户主诉**:靠近底部的思考卡滑动时往上挪一点即「被吸附到底部」;探针全量捕获(4M 缓冲)用户手动演示定位。
- **战争循环实拍**(15:57:35-42+,~270ms/轮):(idx7,off1~110) atBot=false →250ms→GUARD reanchor→requestScrollToItem(0)→(0,off) atBot=true→17ms→idx 翻回 7(off 不变)→循环。落点 off 不清偿=永续战争。
- **三层根因**:①零高横幅带(idx0-6=恒驻 banner 槽位不可见时 0 高)→贴底锚 firstVisibleItemIndex 双稳(0↔首实项,同物理位 off 不变,42.041: (0,47)→17ms→(7,47));②isAtBottom=(idx==0&&off<100) 随 idx 振荡;③GUARD 重锚无死区,微离底也拉回绝对底。
- **链传导解除缺口**:卡内拖拽到边经守卫 drag 通道自然传导外层(嵌套 dispatch)不置外层 isScrollInProgress→autoScroll 不解除→250ms 后 GUARD 把用户刚挪的位置吸回底(用户字面场景)。
- **修复**(b7ee53ea):①ChatScrollController GUARD 死区 REANCHOR_MIN_OFF_PX=120(materiallyOff 入 snapshotFlow 键);②CardFlingLeakGuard.onUserChain(仅 UserInput)→挂载点接 onExpandDeparture 解除 autoScroll。
- **验证**:单测 8 用例绿(含 disarm 分类/钩子触发);真机 T1 贴底静止 6s 零 GUARD 重锚(修前~270ms/轮)、T2 卡内链传导后无吸附。待用户验收。

## 已完结卡片迁入（2026-09-29）

### **#476 贴底思考卡上移即吸附跳底(GUARD 重锚拉锯战)** `chat-ui`
  - 用户演示取证:零高横幅带 idx 双稳+GUARD 无死区→微离底重锚永不清偿~270ms 自持循环;卡内链传导不解除 autoScroll 加剧
  - 已修(双管):GUARD 死区 120px+链传导 onUserChain 解除武装;单测+真机 T1/T2 PASS,待用户验收
  - 迁入依据：用户验收通过(2026-09-29「似乎没啥问题了」):贴底思考卡上移不再被吸附。全链路:探针捕获用户演示→根因(零高横幅带 idx 双稳+GUARD 无死区拉锯战+链传导不解除武装)→双管根修(b7ee53ea)→单测 8 用例+真机 T1/T2→用户复测通过（backlog.sh migrate 2026-09-29）
