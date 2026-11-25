package com.edith.app.alexa

import com.edith.app.core.net.HttpClients
import okhttp3.OkHttpClient

/**
 * Android's Alexa client (K2.5): the shared [AlexaApi] over Ktor on the given OkHttp client, which must not follow redirects (the API follows them
 * by hand, so it can keep the cookies set along the way). A function named like the class, so no caller or test changed.
 */
fun AlexaApi(
    http: Lazy<OkHttpClient>,
    endpointsFor: (AmazonSite) -> AlexaEndpoints = { AlexaEndpoints.of(it.site) },
): AlexaApi = AlexaApi(lazy { HttpClients.over(http.value.newBuilder().followRedirects(false).build()) }, endpointsFor)
