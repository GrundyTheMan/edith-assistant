package com.edith.app.core.error

// The Darwin engine reports its failures as its own exception types; K4.5 maps them when the iPhone app talks to the network.
internal actual fun Throwable.isSocketTimeout(): Boolean = false

internal actual fun Throwable.isUnreachable(): Boolean = false

// K4.5: the Darwin engine's certificate-pin callback reports a mismatch itself (through the pin check's own flag).
internal actual fun Throwable.isCertificateProblem(): Boolean = false
