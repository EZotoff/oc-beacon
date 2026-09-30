# 487-fence-streaming（2026-09-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## E2E 真机终判（2026-09-30 21:12，houji v2 longcat，代码块轮）

- 发送「Write a Kotlin file about 50 lines」→ 50s 流式窗口仪器判读：MDPilot 43 批全量放行（每批 gate release=N **held=0**、releasedTotal==snapshotTotal——旧语义下代码块期 held 会累积数百字符）；MDResize 高度序列 96→3108 **严格单调**（连续 d=60 增量=一行代码一行高地流出，正是行级放行的视觉形态）
- 完结换装干净：A11yDiag path=render src=syncSmall len=260 Success → path=preParsed chunked=false len=1207 Success（<2048 同步终态，Loading=0）；RESETKEY=0 / nonPrefix=0 / FATAL=0；HFLICK 4 条均轮界正常增删；d=- 扫描命中全为 MIUI 输入事件（deviceId=-1），高度域零负向
- 结论：#487 流式代码块行级放行真机验证通过——代码像正文一样逐行流式输出，等待用户视觉验收
