package com.edith.app.voice.own

/**
 * The audio callback handed to sherpa-onnx's `generateWithCallback`. Its native code calls `invoke([F)Ljava/lang/Integer;`
 * on this very object by name, so it is a named class with a keep rule (proguard-rules.pro), not a lambda: R8 renames a
 * lambda's class and rewrites its `invoke`, and the release build then aborts with `NoSuchMethodError` (found by the C36
 * spike on Edith_Release_Test, 2026-11-14). Returns 1 to go on and 0 to stop.
 */
class SherpaCallback(private val onSamples: (FloatArray) -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (onSamples(samples)) 1 else 0
}
