@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.google.browser

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The system's web-authentication sheet for the browser sign-in (K4.4): Safari's own page in a sheet over Edith, which shares the browser's
 * sign-ins (so Google may sign the owner in with one tap) and hands back the address it ended on. It needs no URL scheme registered in
 * Info.plist: the system watches for the callback scheme itself. Nothing is logged (the address carries the code).
 */
class IosAuthSession : AuthSession {
    // The sheet and the object that names its window are held here until it ends: the system only keeps weak references.
    private var current: ASWebAuthenticationSession? = null
    private var anchor: Anchor? = null

    override suspend fun authenticate(url: String, callbackScheme: String): AuthSessionResult = withContext<AuthSessionResult>(Dispatchers.Main) {
        val start = NSURL.URLWithString(url) ?: return@withContext AuthSessionResult.Failed("The sign-in address could not be read.")
        suspendCancellableCoroutine<AuthSessionResult> { cont ->
            val session = ASWebAuthenticationSession(start, callbackScheme) { callback, error ->
                current = null
                anchor = null
                val address = callback?.absoluteString
                cont.resume(
                    when {
                        address != null -> AuthSessionResult.Callback(address)
                        error?.code == ASWebAuthenticationSessionErrorCodeCanceledLogin -> AuthSessionResult.Cancelled
                        else -> AuthSessionResult.Failed("The sign-in sheet ended without an answer.")
                    },
                )
            }
            val presenter = Anchor()
            session.presentationContextProvider = presenter
            session.prefersEphemeralWebBrowserSession = false
            current = session
            anchor = presenter
            if (!session.start()) {
                current = null
                anchor = null
                cont.resume(AuthSessionResult.Failed("The sign-in sheet could not be shown."))
            }
            cont.invokeOnCancellation { session.cancel() }
        }
    }

    /** Names the window the sheet appears over: the app's key window. */
    private class Anchor : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor {
            val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
            val windows = scenes.flatMap { s -> s.windows.filterIsInstance<UIWindow>() }
            return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: UIWindow()
        }
    }
}
