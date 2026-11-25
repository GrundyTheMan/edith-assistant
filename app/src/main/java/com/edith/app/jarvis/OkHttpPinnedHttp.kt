package com.edith.app.jarvis

import android.annotation.SuppressLint
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.net.HttpClients
import com.edith.app.core.secrets.SecretStore
import io.ktor.client.HttpClient
import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The Android engine hook of the shared Jarvis client (K2.4): a Ktor client over a copy of the app's secure OkHttp client (https only, no
 * redirects to http) whose trust manager asks the [PinCheck] about the server's first certificate, with connections that give up after
 * 8 s, calls after 40 s and reads after 30 s unless a call says otherwise. The iPhone's version is K4.5.
 */
class OkHttpPinnedHttp(private val http: Lazy<OkHttpClient>) : PinnedHttp {
    @SuppressLint("CustomX509TrustManager")   // Deliberate: the PC's certificate is self-signed, so trust is the pin (PinCheck), not a CA. With no pin yet (probe) it only reads the fingerprint, and nothing secret is sent.
    override fun client(check: PinCheck, readTimeoutS: Long?, callTimeoutS: Long?): HttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("no client certificates")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Jarvis sent no certificate")
                if (!check.accepts(leaf.encoded)) throw CertificateException("This isn't the PC Edith paired with")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val okHttp = http.value.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(readTimeoutS ?: 30, TimeUnit.SECONDS)
            .callTimeout(callTimeoutS ?: 40, TimeUnit.SECONDS)
            .build()
        return HttpClients.over(okHttp)
    }
}

/** Android's Jarvis accounts: the shared class over OkHttp, pinned to the PC's certificate (K2.8). Keeps every caller as it was. */
fun JarvisAccounts(secrets: SecretStore, http: Lazy<OkHttpClient>, dispatchers: AppDispatchers): JarvisAccounts =
    JarvisAccounts(secrets, OkHttpPinnedHttp(http), dispatchers)
