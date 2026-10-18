# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ─── Koog (AI agent framework by JetBrains) ────────────────────────────────
# koog-http-client-okhttp only has JVM variants. When included in an Android
# build, R8 full-mode (AGP 9.x default) detects the JVM-only
# ai.koog.utils.io.Coroutines_jvmKt as a missing class and fails.
# Suppress these warnings since the referencing code is JVM-specific and is
# stripped away by R8 during Android optimization.
-dontwarn ai.koog.utils.io.Coroutines_jvmKt
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean