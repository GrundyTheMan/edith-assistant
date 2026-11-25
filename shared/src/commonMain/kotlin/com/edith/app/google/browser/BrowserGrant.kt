package com.edith.app.google.browser

import kotlin.time.Instant

/**
 * What a browser sign-in got from Google (C17b): the refresh token and a first access token. It's handed to
 * [BrowserGoogle.keep] once Edith knows which account it belongs to (C22: the address decides, so adding an account
 * that's already there replaces its token instead of adding it twice).
 */
class BrowserGrant(
    val refreshToken: String,
    val accessToken: String,
    val expiresAt: Instant,
    val scopes: Set<String>,
)
