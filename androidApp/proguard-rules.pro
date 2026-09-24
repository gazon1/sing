# Add project specific ProGuard rules here.
# See: http://developer.android.com/guide/developing/tools/proguard.html

# ---------------------------------------------------------------------------
# R8/ProGuard — suppress warnings for missing classes (third-party libs)
# ---------------------------------------------------------------------------
# These -dontwarn rules are required for R8 to succeed. Missing classes are
# runtime-optional (e.g., Ktor debug detectors, OpenTelemetry incubating APIs).
-dontwarn ai.koog.utils.io.Coroutines_jvmKt
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue$CopyAnnotations
-dontwarn com.google.auto.value.AutoValue
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedDoubleHistogram
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedDoubleHistogramBuilder
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedLongCounter
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedLongCounterBuilder
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Keep line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Restore original source file name on crash reports.
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Room — keep entities, DAOs, and database classes
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep @androidx.room.RoomDatabase class * { *; }
# Room generates Database_Impl — keep it
-keep class * extends androidx.room.RoomDatabase {
    public static ** INSTANCE;
}
# Room's RoomMasterTable
-keep class androidx.room.RoomMasterTable { *; }

# ---------------------------------------------------------------------------
# Koin — keep annotations used for dependency injection
# ---------------------------------------------------------------------------
-keep class org.koin.core.annotation.** { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}
# Koin module definitions
-keep class * implements org.koin.core.module.Module { *; }

# ---------------------------------------------------------------------------
# kotlinx-serialization — keep Serializable classes and serializers
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep @Serializable data classes by name pattern (Room entities, domain models)
-keepclassmembers @kotlinx.serialization.Serializable class * {
    <fields>;
    <init>(...);
}
# Keep polymorphic serialization
-keep class kotlinx.serialization.json.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }

# ---------------------------------------------------------------------------
# Koog (AI/LLM library)
# ---------------------------------------------------------------------------
-keep class ai.koog.** { *; }
-keepclassmembers class ai.koog.** { *; }

# ---------------------------------------------------------------------------
# Compose — keep Compose runtime and UI classes
# ---------------------------------------------------------------------------
-dontwarn androidx.compose.**
# Compose compiler redirects — keep
-keep class androidx.compose.compiler.plugins.kotlin.** { *; }
# Keep Compose slots metadata
-keep class androidx.compose.runtime.** { *; }

# ---------------------------------------------------------------------------
# Navigation 3
# ---------------------------------------------------------------------------
-keep class androidx.navigation3.** { *; }
-keepclassmembers class androidx.navigation3.** { *; }

# ---------------------------------------------------------------------------
# DataStore
# ---------------------------------------------------------------------------
-keep class androidx.datastore.** { *; }
-keepclassmembers class androidx.datastore.** { *; }

# ---------------------------------------------------------------------------
# kotlin.datetime — keep Instant and related
# ---------------------------------------------------------------------------
-keep class kotlinx.datetime.** { *; }
-keepclassmembers class kotlinx.datetime.** { *; }

# ---------------------------------------------------------------------------
# Our own domain models (most are Room entities or Serializable)
# ---------------------------------------------------------------------------
# Keep TaskId, NoteId, ProjectId, TagId, etc. (value class inline)
-keepclassmembers class com.singularity.todo.** {
    <init>(...);
}
