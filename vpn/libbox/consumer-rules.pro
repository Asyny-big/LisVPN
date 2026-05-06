# libbox / gomobile bindings must NEVER be obfuscated — JNI lookups go by exact class names.
-keep class go.** { *; }
-keep class libbox.** { *; }
-keepattributes Signature, *Annotation*
