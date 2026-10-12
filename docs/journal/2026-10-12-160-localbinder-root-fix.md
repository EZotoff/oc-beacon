# 160-localbinder-root-fix（2026-10-12）

> 状态：已交付待验收
> 关联：#160（2026-08-20 登记 · 2026-08-23 橡皮章 ok）
> 来源：用户反馈（2026-10-12 LeakCanary 复发 trace，dev 包）
> 提交：8b2152134

## 一、定罪

用户提供的 LeakCanary trace（`GcRoot: Global variable in native code`）：

```
OpenCodeConnectionService$LocalBinder  ↓ this$0
→ OpenCodeConnectionService
  Leaking: YES (received Service#onDestroy(), not held by ActivityThread)
  Retaining 3.8 kB in 75 objects
```

根因链：libbinder 的原生活全局引用持有在途 IBinder → `LocalBinder` 为
**inner class**，其隐式 `this$0` 强引用 → 已 `onDestroy` 的 Service 被钉死。

## 二、为何 2026-08-20 的修复未闭合

2026-08-20（d8331596）的四段修复全部作用在**客户端持有链**：reconnectServer
孤儿 job 取消、SSE `takeWhile` 守卫、`connect()` `isActive` 守卫、HomeViewModel
卫生项（`onCleared` 清 `serviceBinder` + `onBindingDied`/`onServiceDisconnected`）。
journal 原文即注明「结构性根治…未做」。客户端全部持有点清空后，**原生侧**
（libbinder 全局引用）仍持有 binder，而 inner class 的 `this$0` 正是把 Service
与之绑定的那一环——本 trace 复发的就是这条被显式遗留的路径。
2026-08-23 acceptance 将 #160 以「橡皮章 ok」收口，属于过早关闭。

## 三、根因修复

`service/OpenCodeConnectionService.kt`：

- `inner class LocalBinder : Binder()` → **嵌套类** `class LocalBinder(service) : Binder()`
  （消除 `this$0`）。
- Service 引用改经 `WeakReference` 持有；`getService()` 返回类型收窄为可空，
  Service 销毁后返回 null。
- `binder = LocalBinder(this)`（构造点唯一）。
- 调用方（HomeViewModel:159/172/349/460）经核查已全部空安全（`?.` / `?: return`），
  无签名破坏。

## 四、验证（红绿）

新增 `service/OpenCodeConnectionServiceBinderLeakTest.kt`——源码层结构性不变量
（运行时无关、确定性），两条断言：①LocalBinder 无类型可赋给 Service 的实例字段
（即无强外部引用）；②恰好持有一个 `WeakReference` 字段。

- **RED**（修复前，`:app:testDevDebugUnitTest`）：2 tests, 2 failed —
  `OpenCodeConnectionServiceBinderLeakTest.kt:29`（强引用字段存在）+
  `:40`（WeakReference 计数=0）。
- **GREEN**（修复后）：`BUILD SUCCESSFUL`，2/2 通过。
- 编译面：main + test 全量编译通过（编译即覆盖全部调用方签名）。

## 五、残留

全量单测 4107 中 1 失败：`SupervisorPollCoordinatorTest > notifies root health on
error peak rise`（mockk `coVerify notifyRootHealth` 未调用，SupervisorPollCoordinator.kt:85）。
与本改动零交集（supervisor 轮询域，孤立复跑稳定失败）——属他人近期 supervisor
工作引入的既有失败，未扩大范围处理。
