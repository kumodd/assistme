# ============================================================
# 2nd Mind — Offline AI Memory (primary-app) R8 / ProGuard Rules
# ============================================================
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx.serialization (BLE protocol, Keystore bundle, UOAI DTOs, Local Whisper DTOs)
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

# Keep WorkManager workers & Hilt EntryPoints instantiated via reflection
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep @dagger.hilt.EntryPoint interface com.personalai.** { *; }

# Keep Room entities/enums serialized by name
-keepclassmembers enum com.personalai.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp / Okio optional platform classes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
