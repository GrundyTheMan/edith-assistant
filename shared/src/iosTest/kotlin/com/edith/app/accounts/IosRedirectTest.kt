package com.edith.app.accounts

import kotlin.test.Test
import kotlin.test.assertEquals

/** K4.6/K4.7: the redirect addresses the providers register for the iPhone app. */
class IosRedirectTest {
    @Test fun googleReversesTheClientId() {
        assertEquals("com.googleusercontent.apps.123-abc:/oauth2redirect", IosGoogleAccounts.redirectFor("123-abc.apps.googleusercontent.com"))
        assertEquals("", IosGoogleAccounts.redirectFor("not-a-google-client"))
        assertEquals("", IosGoogleAccounts.redirectFor(""))
    }

    @Test fun microsoftUsesTheBundleId() {
        assertEquals("msauth.com.example.edith://auth", IosMicrosoftAccounts.redirectFor("com.example.edith"))
        assertEquals("", IosMicrosoftAccounts.redirectFor(""))
    }
}
