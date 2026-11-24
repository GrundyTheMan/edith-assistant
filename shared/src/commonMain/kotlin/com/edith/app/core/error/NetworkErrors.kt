package com.edith.app.core.error

/** The connection timed out while waiting for data. */
internal expect fun Throwable.isSocketTimeout(): Boolean

/** The server couldn't be reached at all: refused, no route, or a name that doesn't resolve. */
internal expect fun Throwable.isUnreachable(): Boolean

/**
 * The TLS handshake was refused because of the server's certificate (a pinned certificate that no longer matches, K2.4). On Android and the
 * desktop that is a `CertificateException` somewhere in the cause chain; the iPhone's Darwin engine reports it through its own pin check (K4.5).
 */
fun Throwable.isCertificateFailure(): Boolean = generateSequence(this) { it.cause }.any { it.isCertificateProblem() }

internal expect fun Throwable.isCertificateProblem(): Boolean
