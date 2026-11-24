package com.edith.app.tools.web

import com.edith.app.core.dispatchers.AppDispatchers
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val TIMEOUT_S = 10L

/**
 * The page reader on Android: Ktor over OkHttp, 10 s, no redirects of its own (the reader follows them by hand so every hop's host
 * is checked), and [dns] (PublicOnlyDns) refusing names that resolve to a private address.
 */
fun PageReader.Companion.over(
    baseClient: Lazy<OkHttpClient>,
    dispatchers: AppDispatchers,
    dns: Dns = PublicOnlyDns(),
    hostAllowed: (String) -> Boolean = { !PrivateNetwork.isPrivateHost(it) },
): PageReader = PageReader(
    lazy {
        val okHttp = baseClient.value.newBuilder()
            .dns(dns)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS).callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            followRedirects = false   // by hand
            expectSuccess = false
        }
    },
    dispatchers, hostAllowed,
)

/** Refuses names that resolve to any private address, checked on the addresses OkHttp will actually connect to. */
class PublicOnlyDns(private val base: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = base.lookup(hostname)
        if (addresses.any(::isPrivateAddress)) throw PrivateAddressException(hostname)
        return addresses
    }
}

/** A page's address is on a private network, so it isn't fetched. */
class PrivateAddressException(host: String) : UnknownHostException("$host is on a private network"), PrivateAddress

fun isPrivateAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return true
    val b = address.address.map { it.toInt() and 0xff }
    return when (address) {
        is Inet4Address -> PrivateNetwork.isPrivateV4(b[0], b[1])
        is Inet6Address -> b[0] and 0xfe == 0xfc   // fc00::/7, unique local
        else -> false
    }
}
