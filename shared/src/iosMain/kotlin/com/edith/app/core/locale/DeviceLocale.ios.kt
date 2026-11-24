package com.edith.app.core.locale

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

actual fun deviceLanguageTag(): String = NSLocale.currentLocale.localeIdentifier.replace('_', '-')
