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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    private val http: Lazy<OkHttpClient>,
    private val clock: Clock,
    private val baseUrl: String = BASE_URL,
    private val random: SecureRandom = SecureRandom(),
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
                MerossLogin.Ok(MerossCreds(token, key, userId, data.text("email") ?: email, domain, data.text("mqttDomain") ?: domain, installId))
            }
            MerossProtocol.Status.REDIRECT_REGION -> {
                val domain = data?.text("domain")
                if (redirected || domain == null) MerossLogin.Failed("Meross sent Edith to another region twice")
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
        val envelope = MerossProtocol.envelope(params, clock.now().toEpochMilli(), MerossProtocol.nonce(random))
        val request = Request.Builder().url(url)
            .header("Authorization", if (token == null) "Basic" else "Basic $token")
            .header("AppVersion", APP_VERSION).header("vender", "meross").header("AppType", APP_TYPE)
            .header("AppLanguage", "EN").header("User-Agent", "Edith")
            .post(envelope.toString().toRequestBody(JSON))
            .build()
        return try {
            http.value.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build().newCall(request).await().use { response ->
                if (response.code != 200) return Raw.Failed("Meross answered HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                Raw.Done(json.parseToJsonElement(text) as? JsonObject ?: return Raw.Failed("Meross's answer wasn't what Edith expected"))
            }
        } catch (e: IOException) {
            Raw.Failed("Meross can't be reached right now")
        } catch (e: SerializationException) {
            Raw.Failed("Meross's answer wasn't what Edith expected")
        }
    }

    companion object {
        const val BASE_URL = "https://iotx-us.meross.com"
        private const val APP_VERSION = "2.0"
        private const val APP_TYPE = "MerossIOT"
        private const val CALL_TIMEOUT_S = 6L
        private val JSON = "application/json".toMediaType()
    }
}

/** Runs the call without blocking a thread; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
    })
}
