# Miuix
-keep class top.yukonga.miuix.** { *; }

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
