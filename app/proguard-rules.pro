# Miuix / navigation
# Compose UI 按依赖自带的 consumer rules 正常收缩。
# miuix-nav 的 HomeRoute 使用编译期生成的 Kotlin Serialization serializer；
# 路由恢复不依赖反射，序列化库自带 consumer rules，无需全量 keep UI 类。

# Xposed module entry - MUST keep, LSPosed loads by class name
-keep class hook.HyperBackscreen.hook.ModuleMain { *; }
# ModuleMain 拆分出来的各作用域 Hook 类：原来这些代码都在 ModuleMain 里、随它整体 keep，
# 拆分后保持同样待遇（不改名、不内联），release 日志里的异常栈仍是可读的类名 / 方法名。
-keep class hook.HyperBackscreen.hook.system.SystemServerHooks { *; }
-keep class hook.HyperBackscreen.hook.subscreen.SubScreenCenterHooks { *; }
-keep class hook.HyperBackscreen.hook.appindex.SubscreenAppIndex { *; }
-keep class hook.HyperBackscreen.hook.theme.ThemeManagerHooks { *; }
-keep class hook.HyperBackscreen.hook.theme.ThemeResourceRepair { *; }
-keep class hook.HyperBackscreen.hook.assistant.PersonalAssistantHooks { *; }
-keep class hook.HyperBackscreen.hook.util.HookReflection { *; }
-keep class hook.HyperBackscreen.bridge.** { *; }
-keep class hook.HyperBackscreen.common.Constants { *; }
-keep class hook.HyperBackscreen.app.** { *; }

# Xposed API - don't warn about missing classes
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# Compose
-dontwarn androidx.compose.**
