# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
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

# ---------------------------------------------------------------------------
# R8/ProGuard keep rules — added for #639 (isMinifyEnabled = true)
# ---------------------------------------------------------------------------

# ─── Kotlin reflection ────────────────────────────────────────────────────
# Required by Koin's runtime reflection (module scanning,Qualifiers, etc.).
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# ─── Koin ─────────────────────────────────────────────────────────────────
# io.insert-koin:koin-core 4.x uses reflection to:
#   - scan for Module definitions (KoinComponent, etc.)
#   - resolve Qualifier annotations
#   - instantiate declared dependencies at runtime
-keep class org.koin.** { *; }
-keep class org.koin.core.** { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
    @org.koin.core.qualifier.* <fields>;
}
# Koin 4.x internal metamodels — used for module building and scoping
-keep class org.koin.mp.** { *; }
-keep class io.insertkoin.** { *; }
# Koin annotations (used at compile-time by koin-ksp but also retained at runtime for reflection-based resolution)
-keep class io.insert.koin.** { *; }

# ─── Room ─────────────────────────────────────────────────────────────────
# androidx.room3.Room uses annotation processing at compile time (room3-compiler)
# and reflection at runtime for:
#   - database creation (abstract Database class)
#   - DAO method binding
#   - type converters
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
# Room-generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.AbstractRoomMigration
# Room's KSP generates LambdaMeteredExecutor etc.; keep their visibility
-keepclassmembers class * {
    @androidx.room.Generated * <methods>;
}

# ─── kotlinx-serialization ─────────────────────────────────────────────────
# kotlinx-serialization-json uses Kodegenerator (kotlinx-serialization-compiler)
# at compile time and relies on @Serializable descriptors and
# RuntimeHelpers at runtime.
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt

# Serializable classes must be kept by name (their descriptor is referenced at runtime)
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(*);
    <fields>;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.* <methods>;
}

# kotlinx-serialization-compiler-plugin generated helpers (used in serializers)
-keep class kotlinx.serialization.internal.** { *; }
-keep class kotlinx.serialization.builtins.** { *; }
-keepclassmembers class kotlinx.serialization.descriptors.* {
    *** Companion;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName * <fields>;
}

# The JSON encoder/decoder — keep their class names so the runtime can find them
-keep class kotlinx.serialization.json.** { *; }
