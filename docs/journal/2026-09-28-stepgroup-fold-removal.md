# stepgroup-fold-removal（2026-09-28）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 执行与验证证据

### 提交链
- 433a3d1c 手术一:统一渲染树(isStreaming 双分支删除,StepGroupCard 恒走+isStreaming 仅驱动 asyncParse 解析分流;heavyComposed 门控+桩帧分支+HflickProbe STEP 探针退役;ChunkAssistantItems 增 asyncParse 参数透传,小组/窗口化两路径传 !isStreaming)
- acf7d4ba 手术二:死代码族清除(ChatEntry.StepGroupHead/Body 类+裂变路径+skipStepGroupItem 三层参数链+StepGroupFoldRow+stepGroupStateKey+LocalFoldRowYReport/LocalFoldRowClick 死链(REPIN 遗迹,无 provide 者恒 null)+ReasoningBlock 死链引用+LocalStepGroupComputing+stubHeightPx+SG_BODY_FADE_MS+chat_msg_tail_summary×15 语言+StepGroupLazySplitTest 148 行)

### 自动化验证
- 编译绿×2(手术一/二各一次);全量单测绿(testDevDebugUnitTest --rerun;首跑红=StepGroupHeightLedgerTest 4 处 stubHeightPx 残留引用,随测试对象退役删除后绿)
- i18n check PASSED:916 keys × 14 languages(删 1 key 后全一致)

### 真机验证(小米 houji,ses_f19eeb1d,4199 v1)
- **RED→GREEN 判决**:修复前 10 轮多 step POST 完结塌缩 10/10(d=-237/-401→+225/+365,65-132ms 回弹,RESIZE 两连跳上屏);修复后(versionCode=1790602346)同参数 10 轮 **0/10**(168 个 RESIZE 零 d≤-80 负跳)
- 对照组(修复前):单 step 纯文本轮 0/3 负跳——step≥2 为唯一 load-bearing 变量,与 StepGroup 互换根因闭环
- 滚动流畅性:16 次 swipe 穿越历史大 turn(含表格轮)**零掉帧**(Choreographer Skipped=0)——窗口化体系无冻结回归
- 重进会话加载路径:RESIZE 0 负跳 GREEN(历史高度稳定,账本暖)

### CBM/grep 复扫(用户后验要求)
- grep 全仓(app/src,*.kt+*.xml):已删符号(StepGroupFoldRow/skipStepGroupItem/stubHeightPx/LocalStepGroupComputing/LocalFoldRowY*/StepGroupHead/Body/HflickProbe/expandedStepGroups/chat_msg_tail_summary/SG_BODY_FADE_MS/heavyComposed)代码引用**清零**,残留全为合法历史注释(清理批次说明+CardExpandReveal 描述文字)
- [DEBUG-hflick] PLAN 探针(ChatMessageList 741-803)保留:探测对象 entries key 序列仍活,属 #473 探针清扫域非本次范围
- codebase-memory 图索引(fast)已建:project=oc-beacon

## Bug B/C(展开吞卡/下拖)复测证据(2026-09-28 21:49)

- 定位:tap 扫描(x=140, y=700-1700 每 60px)命中 y=1480 可展开卡
- 展开: RESIZE 826->1020 (d=+194) 单条目变化
- 收起: RESIZE 1020->826 (d=-194) 与展开精确镜像守恒;RESERVE align-flip overflow=0(帽零溢出);无其他条目 RESIZE(无吞卡);无 dispatch=贴底免派发正常
- toggle 风暴(5 连点@550ms): +194/-194/+194/-194/+194 严格交替,零漂移零多余扰动
- 截图留档: /tmp/462_expanded.png /tmp/462_collapsed.png(视觉终判留用户体感验收)
- 结论:展开/收起只影响目标卡自身,上方内容零扰动——吞卡/下拖仪器面消失

## 已完结卡片迁入（2026-09-28）

### **#426 结构裂变死代码清理(批次十三退役遗留)** `cleanup`
  - buildChatEntries StepGroupHead/Body 发射机+sliceStepGroupBodies+SGB 探针+LARGE_STEP_GROUP_WEIGHT 常量已休眠
  - 2026-09-28 已随 #422 清理批次执行完毕(acf7d4ba):裂变发射机/StepGroupHead-Body/skipStepGroupItem/相关常量与注释全删,grep 复扫清零——本卡可关闭
  - 迁入依据：已随 #422 清理批次执行完毕(acf7d4ba):裂变发射机/StepGroupHead-Body 条目族/skipStepGroupItem 参数链/相关常量全删,grep 复扫代码引用清零（backlog.sh migrate 2026-09-28）
