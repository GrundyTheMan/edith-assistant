package com.edith.app.meross

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How a sign-in ended (C26 §1). */
sealed interface MerossLogin {
    data class Ok(val creds: MerossCreds) : MerossLogin
    /** Meross wants the code from the owner's authenticator or email. */
    data object NeedsCode : MerossLogin
    data object WrongCode : MerossLogin
    data object BadLogin : MerossLogin
    data object TooManyTokens : MerossLogin
    data class Failed(val why: String) : MerossLogin
}

/**
 * Meross's HTTP API (C26 §1): sign in, the device list, sign out. Every call is the same signed envelope
 * ([MerossProtocol.envelope]). A sign-in issues a token, and Meross limits how many an account may hold, so the token
 * is kept and reused, and returned to Meross when the owner disconnects. Request and response bodies, tokens and keys
 * are never logged.
 */
class MerossApi(
    private val http: Lazy<HttpClient>,
    private val clock: Clock,
    private val baseUrl: String = BASE_URL,
    private val random: SecureRandom = SecureRandom(),
    /** Which addresses Meross may send Edith to (a region redirect, the API and MQTT hosts); tests answer from a local server. */
    private val trusted: (String) -> Boolean = MerossProtocol::isTrustedDomain,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Signs in. A region redirect is followed once; [mfaCode] only after [MerossLogin.NeedsCode]. */
    suspend fun login(email: String, password: String, mfaCode: String? = null, installId: String): MerossLogin =
        login(baseUrl, email, password, mfaCode, installId, redirected = false)

    private suspend fun login(base: String, email: String, password: String, mfaCode: String?, installId: String, redirected: Boolean): MerossLogin {
        val params = buildJsonObject {
            put("email", email)
            put("password", MerossProtocol.md5(password))
            put("accountCountryCode", "us")
            put("encryption", 1)
            put("agree", 0)
            put("mobileInfo", buildJsonObject {
                put("deviceModel", "Android"); put("mobileOsVersion", "Android"); put("mobileOs", "Android")
                put("uuid", installId); put("carrier", "")
            })
            if (mfaCode != null) put("mfaCode", mfaCode)
        }
        val answer = post("${MerossProtocol.apiBase(base)}/v1/Auth/signIn", params, token = null)
        val body = when (answer) {
            is Raw.Failed -> return MerossLogin.Failed(answer.why)
            is Raw.Done -> answer.body
        }
        val data = body["data"].obj()
        return when (val status = body.int("apiStatus")) {
            MerossProtocol.Status.OK -> {
                val token = data?.text("token"); val key = data?.text("key"); val userId = data?.text("userid")
                if (token == null || key == null || userId == null) return MerossLogin.Failed("Meross's answer wasn't what Edith expected")
                val domain = data.text("domain") ?: base
                val mqttDomain = data.text("mqttDomain") ?: domain
                val creds = MerossCreds(token, key, userId, data.text("email") ?: email, domain, mqttDomain, installId)
                if (!trusted(domain) || !trusted(mqttDomain)) {
                    // The token is only ever sent to Meross: hand it back and keep nothing.
                    Log.w(LogTag.Home, "Meross sign-in named an address outside meross.com")
                    logout(creds.copy(domain = base))   // handles its own failures (it only logs them)
                    return MerossLogin.Failed(UNTRUSTED)
                }
                MerossLogin.Ok(creds)
            }
            MerossProtocol.Status.REDIRECT_REGION -> {
                val domain = data?.text("domain")
                // The password goes to the address a response names, so it must be a Meross one (https, under meross.com).
                if (redirected || domain == null) MerossLogin.Failed("Meross sent Edith to another region twice")
                else if (!trusted(domain)) {
                    Log.w(LogTag.Home, "Meross sign-in was sent to an address outside meross.com")
                    MerossLogin.Failed(UNTRUSTED)
                }
                else login(domain, email, password, mfaCode, installId, redirected = true)
            }
            MerossProtocol.Status.MFA_REQUIRED -> MerossLogin.NeedsCode
            MerossProtocol.Status.WRONG_MFA -> MerossLogin.WrongCode
            MerossProtocol.Status.WRONG_CREDENTIALS, MerossProtocol.Status.UNKNOWN_ACCOUNT -> MerossLogin.BadLogin
            MerossProtocol.Status.TOO_MANY_TOKENS -> MerossLogin.TooManyTokens
            else -> {
                Log.w(LogTag.Home, "Meross sign-in: apiStatus $status")
                MerossLogin.Failed("Meross refused the sign-in (code $status)")
            }
        }
    }

    /** The account's devices. */
    suspend fun devices(creds: MerossCreds): MerossReply<List<MerossDevice>> {
        val body = when (val a = post("${MerossProtocol.apiBase(creds.domain)}/v1/Device/devList", JsonObject(emptyMap()), creds.token)) {
            is Raw.Failed -> return MerossReply.Failed(a.why)
            is Raw.Done -> a.body
        }
        val status = body.int("apiStatus")
        if (status in MerossProtocol.Status.TOKEN_REJECTED) return MerossReply.Rejected
        if (status != MerossProtocol.Status.OK) {
            Log.w(LogTag.Home, "Meross device list: apiStatus $status")
            return MerossReply.Failed("Meross refused the device list (code $status)")
        }
        val list = (body["data"] as? JsonArray)?.mapNotNull { e ->
            val d = e.obj() ?: return@mapNotNull null
            val uuid = d.text("uuid") ?: return@mapNotNull null
            MerossDevice(uuid, d.text("devName") ?: "Meross device", d.text("deviceType").orEmpty(), d.int("onlineStatus") == 1)
        }.orEmpty()
        return MerossReply.Ok(list)
    }

    /** Gives the token back to Meross (it limits how many an account may hold). A failure is only logged. */
    suspend fun logout(creds: MerossCreds) {
        when (val a = post("${MerossProtocol.apiBase(creds.domain)}/v1/Profile/logout", JsonObject(emptyMap()), creds.token)) {
            is Raw.Failed -> Log.w(LogTag.Home, "Meross sign-out: ${a.why}")
            is Raw.Done -> Unit
        }
    }

    private sealed interface Raw {
        data class Done(val body: JsonObject) : Raw
        data class Failed(val why: String) : Raw
    }

    private suspend fun post(url: String, params: JsonObject, token: String?): Raw {
        val envelope = MerossProtocol.envelope(params, clock.now().toEpochMilliseconds(), MerossProtocol.nonce(random))
        return try {
            val response = http.value.post(url) {
                timeout { requestTimeoutMillis = CALL_TIMEOUT_S * 1000 }
                header("Authorization", if (token == null) "Basic" else "Basic $token")
                header("AppVersion", APP_VERSION); header("vender", "meross"); header("AppType", APP_TYPE)
                header("AppLanguage", "EN"); header("User-Agent", "Edith")
                setBody(TextContent(envelope.toString(), JSON))
            }
            if (response.status.value != 200) return Raw.Failed("Meross answered HTTP ${response.status.value}")
            val text = response.bodyAsText()
            Raw.Done(json.parseToJsonElement(text) as? JsonObject ?: return Raw.Failed("Meross's answer wasn't what Edith expected"))
        } catch (e: IOException) {
            Raw.Failed("Meross can't be reached right now")
        } catch (e: SerializationException) {
            Raw.Failed("Meross's answer wasn't what Edith expected")
        }
    }

    companion object {
        const val BASE_URL = "https://iotx-us.meross.com"
        const val UNTRUSTED = "Meross named an address that isn't Meross's, so Edith stopped and sent nothing there"
        private const val APP_VERSION = "2.0"
        private const val APP_TYPE = "MerossIOT"
        private const val CALL_TIMEOUT_S = 6L
        private val JSON = ContentType.Application.Json
    }
}
