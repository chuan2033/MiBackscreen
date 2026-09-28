# Miuix / navigation
# Compose UI 按依赖自带的 consumer rules 正常收缩。
# miuix-nav 的 HomeRoute 使用编译期生成的 Kotlin Serialization serializer；
# 路由恢复不依赖反射，序列化库自带 consumer rules，无需全量 keep UI 类。

# Xposed module entry - MUST keep, LSPosed loads by class name
-keep class hook.HyperBackscreen.hook.ModuleMain { *; }
-keep class hook.HyperBackscreen.bridge.** { *; }
-keep class hook.HyperBackscreen.common.Constants { *; }
-keep class hook.HyperBackscreen.app.** { *; }

# Xposed API - don't warn about missing classes
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# Compose
-dontwarn androidx.compose.**
