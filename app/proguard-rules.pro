# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class dev.leonardo.ocbeacon.**$$serializer { *; }
-keepclassmembers class dev.leonardo.ocbeacon.** {
    *** Companion;
}
-keepclasseswithmembers class dev.leonardo.ocbeacon.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor（#118 D2-29：原全库保留 → 收窄为反射/序列化必要部分；release 构建后
# 需连接冒烟验证——SSE/内容协商/OkHttp 引擎如出现 NoSuchMethodError 再补规则）
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.client.plugins.contentnegotiation.** { *; }
-keep class io.ktor.client.plugins.sse.** { *; }
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.ktor.utils.io.** { *; }
# kotlinx.coroutines：官方对 R8 完全支持，无需 keep（移除全库保留）
-dontwarn kotlinx.atomicfu.**
-dontwarn io.netty.**
-dontwarn com.typesafe.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Markdown Renderer (mikepenz) — keep state/model classes to prevent R8 breaking async parsing
-keep class com.mikepenz.markdown.** { *; }
-keep class org.intellij.markdown.** { *; }

# Syntax Highlighting (dev.snipme/highlights) — model classes use kotlinx.serialization
-keep class dev.snipme.highlights.** { *; }

# Compose LazyListState — reflection access for SSE drift compensation
# (bypass requestScrollToItem's scroll{} mutex cancellation)
-keep class androidx.compose.foundation.lazy.LazyListState { *; }
-keep class androidx.compose.foundation.lazy.LazyListScrollPosition { *; }

# ConnectBot termlib — keep public API and native method signatures.
-keep public class org.connectbot.terminal.** { public *; }
-keepclasseswithmembernames class * { native <methods>; }

# zstd-jni: JNI 链接依赖类名，禁止混淆/重命名（luben/zstd-jni README）
-keep class com.github.luben.zstd.** { *; }

# 331365999 家族加固（2026-08-29）：pausable prefetch 预组合在含非局部 return
# 的 item 内容上崩溃（IntStack.peek2 AIOOBE）。OpenCodeApp.onCreate 已置
# isPausableCompositionInPrefetchEnabled=false；R8 侧固化该值，防止优化器
# 基于默认 true 折叠分支（flag 文档模板同款）。
-assumevalues class androidx.compose.foundation.ComposeFoundationFlags {
    public static boolean isPausableCompositionInPrefetchEnabled return false;
}

# #473 后手①（2026-10-09 用户裁决根修域）：R8 剥离 Log.d/v 调用——release 侧
# 探针族已由 BuildConfig.DEBUG 门控零执行，此条兜底清零「未门控业务级 AppLogger.d」
# 的字符串构建+JNI 写成本。AppLogger 持久化路径（queue.trySend）不受影响——
# 用户下调诊断级别时落库行为保持（trySend 非 Log 调用，不满足无副作用假设）。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
