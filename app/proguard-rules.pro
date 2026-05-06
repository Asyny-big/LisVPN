# LisVPN Android — ProGuard / R8 rules.

# --- libbox (gomobile bindings) ---
-keep class go.** { *; }
-keep class libbox.** { *; }
-keepattributes Signature, *Annotation*

# --- Kotlinx Serialization ---
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lisvpn.android.**$$serializer { *; }
-keepclassmembers class com.lisvpn.android.** { *** Companion; }
-keepclasseswithmembers class com.lisvpn.android.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Hilt ---
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel
-keep class * extends androidx.work.ListenableWorker

# --- Compose ---
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# --- Coroutines / Flow ---
-dontwarn kotlinx.coroutines.flow.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Ktor / OkHttp ---
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Tink ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- General ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
