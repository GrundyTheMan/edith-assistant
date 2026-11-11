# R8 rules for release builds. Libraries added later (kotlinx.serialization, OkHttp, Room) ship their own
# consumer rules; add app-specific keep rules here only when a release build proves they're needed.

# ONNX Runtime (C14 wake word): its JNI code finds Java classes and methods by name (e.g. ai/onnxruntime/TensorInfo
# in OrtSession.run), and onnxruntime-android ships no consumer rules. Without this, R8 renames them and the release
# build aborts with "JNI DETECTED ERROR … java_class == null" as soon as hands-free scores audio (found on
# Edith_Release_Test before the first release, 2026-11-11).
-keep class ai.onnxruntime.** { *; }
