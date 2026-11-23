# ============================================================
# 2nd Mind Recorder Companion (recorder-app) R8 / ProGuard Rules
# ============================================================
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx.serialization (BLE protocol, PersistedPairingRecord, diagnostics)
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.personalai.**$$serializer { *; }
-keepclassmembers class com.personalai.** {
    *** Companion;
}
-keepclasseswithmembers class com.personalai.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.personalai.** { *; }

# Preserve ONNX Runtime JNI classes & native callbacks for Silero VAD
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Preserve Services, BroadcastReceivers, and Room enums
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keepclassmembers enum com.personalai.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
