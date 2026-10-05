# 478-fts-external-content（2026-09-30）

> 状态：✅ 已完结（用户真机验收通过 2026-09-30，commit c2f965ff + 8c45958c）
> 关联：backlog #478（Room 库 837MB 无界增长）· 定罪：2026-09-29-closure-audit T-B
> 来源：用户开工指令「修根因，本次无须自动化 E2E，做好日志/埋点，修好测一测找用户验收」

## 根因与对策（三根因，治二弃一）

| 根因（09-29 dbstat 定罪） | 对策 | 结果 |
|---|---|---|
| ①FTS5 未用外部内容表，全文镜像 613MB=库92% | message_fts 改 external-content（content=cached_parts, content_rowid=rowid），影子表只存倒排 | ✅ 镜像消除 |
| ②FTS 行不随热表修剪（18.1万 vs 热表 6.6k） | 同步下沉 SQLite 触发器（ai/ad, WHEN type='text'）——覆盖 CASCADE/迁移/清扫全路径；MIGRATION_9_10 DROP 旧表+过滤回填 | ✅ 孤儿清除+不再堆积 |
| ③page_size=1024 放大开销 | 原计划 VACUUM 时升 4096——**放弃**（见取舍） | ⚪ 收益 ~5MB 不值得 |

### 修复结构（commit c2f965ff 主体）
- `MessageFtsSchema`：external-content DDL + TRIGGERS + BACKFILL（ContentSearch.kt）
- `MIGRATION_9_10`（OcBeaconDatabase v9→v10）：DROP 旧表 → 新表+触发器 → 过滤回填（只 text 行）
- `DatabaseModule` onOpen 回调：freelist>64MB 兜底 VACUUM（requery 库 auto_vacuum=FULL 常态自动回收，此分支防御性）
- `MessageStore` 手动 FTS 维护整段拆除（indexTextParts/IndexedTextPart/existingTexts 快照判据/fts 字段/SQLITE_IN_CHUNK）
- `searchFts`：role 过滤/取值改 JOIN cached_messages 现值（mm.role）
- 埋点：`[478-migration]`（old=/indexed=/耗时）、`[478-vacuum]`（freelist/file MB/耗时）、`[478-probe]`（upsert/replace 分段计时）——DB 初始化路径用 android.util.Log（防 AppLogger 写库重入）

### 设计取舍（代码注释同步记录）
1. **UPDATE 不挂触发器**：流式 48ms delta append 不重索引（免长 part 每批全文倒排重写+FTS5 段合并风暴）；终值由下一次快照 REPLACE（upsertParts=DELETE+INSERT 必经触发器）收敛——索引更新时机与旧手动维护（仅快照路径）等价。
2. **弃 FTS5 'rebuild' 命令**：rebuild 对 external-content 表全量索引 content 表每一行（含 reasoning），与触发器 WHEN type='text' 冲突——误索引行删除时 ad 触发器不清理（WHEN 不满足），倒排孤儿永久残留（宿主 C10 实证）=根因②新形态复辟。回填用同形同过滤的 INSERT...SELECT。
3. **page_size 升级放弃**：WAL 下 PRAGMA page_size 静默无效须切 journal_mode=DELETE，requery 连接池架构下切换永远无法独占（真机 busy_timeout 8s 仍 code 5 锁死）；external-content 化后稳态库 ~60MB 级，1024→4096 收益仅 ~5MB。

## 验证链

### 宿主 SQLite DDL 全链路（sqlite3 3.53.4）
- A/B 系列：旧式与 external-content 行为对照（回表读列/integrity/倒排一致）；**发现**：external-content 表 COUNT(*)=content 表行数（含非 text 行），埋点 indexed= 改用 _docsize 影子表计数
- C 系列 10 断言全过：过滤回填只索 text（docsize=3/4）·英文命中 ·reasoning 零泄漏·触发器 ai/ad 即时同步·CASCADE 删消息同步·prune 删 part 跟随·被删内容不可搜·REPLACE 删旧插新·UPDATE 滞后（设计内）·bm25+snippet+JOIN 完整查询·integrity ok·C10 反证（误索引 reasoning 行删除后倒排残留）
- FTS5 行 DELETE 不即时收缩倒排段（须 merge）——侧面证实迁移用 DROP 整表物理删的必要性

### 真机复现现场工程（837MB 同构库）
1. 设备原 837MB 库已于昨夜被清（拉出为空壳：user_version=0 无表，7.7MB 全 freelist——疑 DatabaseRecovery 重建，无从回溯，dev 测试包无损）
2. pm clear → 旧 APK（22:30 版, v9 代码）冷启重建 v9 库 → force-stop → exec-out 拉库（**坑：run-as cp /sdcard 被 scoped storage 拒；exec-in 流重定向引号被 adb strip——最终 exec-out cat 拉回 + dd of= 推回，md5 双端校验一致**）
3. 宿主灌 96,000 行×4.1KB 旧形态 FTS 孤儿（repeat() 宿主 sqlite3 缺失→hex+replace 制造分词语境）：804MB（content 388MB+data 406MB）,freelist=0,user_version=9——与定罪现场同构
4. dd 推回设备 → adb install -r 新 APK（c2f965ff 构建）

### 真机迁移实证（2026-09-30 01:08）
- 埋点：`[478-migration] FTS external-content rebuilt: old=96000 indexed=145 rows, 919ms`
- **库文件 832MB → 1.2MB**；docsize=145=cached_parts text 行数（749 总行，604 reasoning/tool 行正确排除）
- message_fts_content 影子表消失（external-content 生效）；user_version=10；freelist=0
- 拉回宿主：integrity-check OK；MATCH 'hello' 命中 2 条真实会话数据（snippet 高亮+messageId 回表+JOIN 全链路）
- 二次冷启：[478-vacuum] 常态静默（freelist=0<64MB 阈值正确不触发），无 warning，进程存活

### 真机踩坑实录（三枚，均已绕开/修复）
1. requery `execSQL` 拒 PRAGMA 语句（code 0 "Queries can be performed using SQLiteDatabase query or rawQuery methods only"）→ PRAGMA/VACUUM 全改 query()
2. onOpen 启动期与 SSE/日志并发写撞锁（code 5）→ busy_timeout 无效（连接池常连接）→ page_size 路径整体放弃后此问题不再可达
3. **requery 建库 auto_vacuum=FULL**（真机实测）——DROP 释放页平台自动归还，这解释了「832MB→1.2MB 无 VACUUM 日志」；也证明生产库当年 837MB 增长与 auto_vacuum 无关（FTS 行从未删除，freelist=0 活页）

### 顺带发现（既有行为，非本次回归，未处理）
- **unicode61 不拆 CJK**：宿主实测连续汉字串=单 token，中文裸词/单字 MATCH 零命中（旧式表同样零命中=A1 对照，旧新行为一致）。KDoc「unicode61 单字分词下中文逐字可命中」认知存疑——中文搜索实际命中面存疑，待后续观察（英文/数字词搜索正常）。若需中文可搜需换 trigram/tokenizer，另立卡处理。

### 回归
- compileDevDebugKotlin 绿；全量 JVM 单测绿（两轮）；受影响三套（MessageStoreTest/SessionListViewModelSearchTest/SessionListViewModelServerSearchTest）绿

## 用户验收清单（建议）
1. 冷启 app 无异常（迁移已在你设备完成，后续启动应无感）
2. 会话列表搜索：输英文词（如会话里出现过的模型名/代码词）应有命中+高亮摘要
3. 观察 设置→Diagnostics 或 logcat `OcBeaconDB` tag：[478-migration] 已在 01:08 出现过；[478-vacuum] 常态不应出现
4. 存储占用：app 信息里数据库体积应 ~1-2MB 级（原来 837MB）

## 已完结卡片迁入（2026-09-30）

### **#478 Room 库 837MB 无界增长源待定位——热表修剪+归档在,库文件仍巨** `data` `perf`
  - 2026-09-29 发掘审计真机实测:dev 包 ocbeacon.db=837MB(800M databases,files/shared_prefs/cache 全<1MB)——增长全在库文件
  - 修剪机制其实存在(MessageStore SESSION_MESSAGE_LIMIT+溢出 zstd 归档后 prune)——嫌疑收窄:FTS 索引行(独立于分层,删会话才清)/归档桶常驻库内/SQLite 自由页无 VACUUM 回收/工具输出 provider 缓存
  - 影响面=存储占用与冷启开销;定罪路径:库表体积普查(sqlite dbstat/各表 COUNT+长度和)→对位修复(FTS 随归档清/周期 VACUUM/归档外移文件系统)
  - 2026-09-29 T-B 普查定罪(844MB 库 dbstat):FTS5 占 778MB/92%——message_fts_content 613MB(181,682 行全文镜像,未用 external-content 表配置)+message_fts_data 162MB(倒排);真实数据仅 ~58MB(cached_parts 22.3+archive_buckets 25.9+cached_messages 3.4+logs 5.9);freelist=0
  - 复合根因:①FTS5 建表未用 content=外部内容表→全文在库内双份;②FTS 行不随热表修剪(181k 行 vs cached_parts 6.6k 行,冷数据未压缩文本永驻——ContentSearch.kt:70 'prune 不删 FTS 行'设计);③page_size=1024 小页放大 btree/溢出链开销;修向=external-content 重建 FTS+迁移回填(+可选 page_size 4096 需 VACUUM 备份路径)——预计回收 ~613MB,稳态 ~230MB
  - 迁入依据：用户真机验收通过 2026-09-30(fb9acfcd 关卡在案,837MB→2.4MB 级);卡面漏迁本次补账（backlog.sh migrate 2026-09-30）
