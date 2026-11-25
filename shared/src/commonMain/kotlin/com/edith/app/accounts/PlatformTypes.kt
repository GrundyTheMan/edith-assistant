package com.edith.app.accounts

/**
 * The three Android objects a sign-in passes around (K2.6a), named here so the shared accounts code can mention them: the screen that shows a
 * sign-in or consent page, the consent screen Google hands back, and what that screen returns. On Android they are the real `Activity`,
 * `PendingIntent` and `Intent` (type aliases, so no Android caller changes); on the iPhone and the desktop they are empty, because sign-in
 * there is the browser's and goes through its own seam, built when the iPhone's sign-in is.
 */
expect class PlatformActivity

expect class PlatformPendingIntent

expect class PlatformIntent
