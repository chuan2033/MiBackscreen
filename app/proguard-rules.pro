# Miuix
# 不需要 keep：miuix 运行时代码零反射（已核对上游源码，无 Class.forName / getDeclaredField /
# ::class.java 用法），AGSL shader 以字符串常量内联，纯 Compose UI 由 R8 正常收缩即可。
# 本项目也未依赖 miuix-nav（唯一涉及 @Serializable 路由的模块）。

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
