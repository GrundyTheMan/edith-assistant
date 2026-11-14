# R8 rules for release builds. Libraries added later (kotlinx.serialization, OkHttp, Room) ship their own
# consumer rules; add app-specific keep rules here only when a release build proves they're needed.

# ONNX Runtime (C14 wake word): its JNI code finds Java classes and methods by name (e.g. ai/onnxruntime/TensorInfo
# in OrtSession.run), and onnxruntime-android ships no consumer rules. Without this, R8 renames them and the release
# build aborts with "JNI DETECTED ERROR … java_class == null" as soon as hands-free scores audio (found on
# Edith_Release_Test before the first release, 2026-11-11).
-keep class ai.onnxruntime.** { *; }

# Jakarta Mail / Eclipse Angus Mail (C24 iCloud mail). It finds its protocol classes by name: each Provider (found
# through META-INF/services) names a Store or Transport class that Session creates with reflection, and the mailcap
# file names content handlers. Without these rules R8 renames them and every IMAP/SMTP connection fails at once with
# NoSuchProviderException ("imaps", "smtp"), as seen on Edith_Release_Test on 2026-11-12; the debug build worked.
-keep class * extends jakarta.mail.Provider { <init>(...); }
-keep class * extends jakarta.mail.Service { <init>(jakarta.mail.Session, jakarta.mail.URLName); }
-keep class * implements jakarta.activation.DataContentHandler { <init>(...); }
-keep class * implements jakarta.activation.spi.** { <init>(...); }
# Its image/gif and image/jpeg handlers use java.awt, which Android lacks; Edith only reads and writes text mail.
-dontwarn java.awt.**
# Its optional SASL login (off unless mail.imap.sasl.enable is set; Edith never sets it) uses javax.security.sasl,
# which Android lacks.
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.**

# sherpa-onnx (C36 Edith's own voice): its native library calls back into these Kotlin classes by name (config
# classes are read field by field from JNI; GeneratedAudio is built from JNI; the TTS callback is invoked by name).
-keep class com.k2fsa.sherpa.onnx.** { *; }
# The callback object given to sherpa-onnx is called from native code by method name and signature.
-keep class com.edith.app.voice.own.SherpaCallback { *; }
