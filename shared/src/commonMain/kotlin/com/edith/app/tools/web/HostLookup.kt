package com.edith.app.tools.web

/**
 * Every address a name resolves to (4 bytes each for IPv4, 16 for IPv6), or an empty list when it does not resolve (K4.2). The iPhone's page reader
 * uses it to refuse a name that points at a private network: Android does the same inside its OkHttp lookup (`PublicOnlyDns`), which Apple's
 * networking has no hook for.
 */
internal expect fun resolveHostAddresses(host: String): List<ByteArray>

/**
 * Whether the page reader may connect to [host]: not a name or address that only means something on a LAN, and not a name that resolves to a
 * private address. A name that does not resolve is allowed: the fetch then fails with "couldn't find it". The lookup here and the one the
 * connection makes are two lookups, so a hostile DNS server could answer differently the second time; Android closes that gap by checking
 * the addresses it connects to, the iPhone does not yet.
 */
internal fun publicHostAllowed(host: String, resolve: (String) -> List<ByteArray> = ::resolveHostAddresses): Boolean =
    !PrivateNetwork.isPrivateHost(host) && resolve(host).none(PrivateNetwork::isPrivateAddress)
