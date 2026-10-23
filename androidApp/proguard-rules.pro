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

# ─── Room databases that arrive through a dependency ────────────────────────
#
# WorkManager ships its own Room 2.6.1 database (androidx.work.impl.WorkDatabase),
# and Room creates it reflectively:
#
#     Room.getGeneratedImplementation()
#         -> Class.forName("androidx.work.impl.WorkDatabase_Impl")
#         -> Class.getDeclaredConstructor()          // no arguments
#
# Room 2.6.1's own consumer rule is
#
#     -keep class * extends androidx.room.RoomDatabase
#
# which keeps the class but, since R8 full mode, no longer keeps the default
# constructor implicitly — R8 full mode dropped that implicit keep, and the
# class is only ever instantiated reflectively, so R8 removed
# WorkDatabase_Impl.<init>() as well. The app then died inside the WorkManager
# auto-initializer, before MainActivity ever ran:
#
#     Unable to get provider androidx.startup.InitializationProvider:
#     NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
#
# The explicit constructor below is exactly what R8 full mode stopped doing on its
# own. Room 3 (androidx.room3) keeps its constructor explicitly in its own consumer
# rules, so only the Room 2 databases that come with a dependency need this.
-keep class * extends androidx.room.RoomDatabase { <init>(...); }

# ─── BuildConfig, read reflectively from `shared` ───────────────────────────
#
# `shared` compiles before `androidApp` generates BuildConfig, so it reads the
# build-time values through reflection:
#
#     Class.forName("com.singularity.todo.BuildConfig")
#         .getField("SUPABASE_URL")
#
# R8 resolves a constant-string Class.forName to a class reference, and then
# treats the class as an ordinary one: it renamed it (com.singularity.todo.BuildConfig
# -> hy0) and removed every field, because nothing in the program reads them
# statically. The reflective read therefore found a class with no SUPABASE_URL
# field, and the release APK threw where the debug APK worked.
#
# Keeping the class and its fields is what makes the reflective read resolve.
-keep class com.singularity.todo.BuildConfig { *; }