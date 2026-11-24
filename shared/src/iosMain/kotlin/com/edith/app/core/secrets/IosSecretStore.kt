@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.core.secrets

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Secrets in the iPhone's Keychain (K4.1; the Keychain bridge of the Phase 0 spike, which ran on a real phone, now behind [SecretStore]).
 * Items are readable after the first unlock (so a background task can use a sign-in) and never leave the device (no iCloud sync).
 * The small Core Foundation values handed to the query are not released one by one: a handful of bytes per read, to be tidied when the
 * sign-ins arrive (K4.4).
 */
class IosSecretStore(private val service: String = "com.edith.app") : SecretStore {

    private fun query(key: String) = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr).also {
        CFDictionaryAddValue(it, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(it, kSecAttrService, CFBridgingRetain(service))
        CFDictionaryAddValue(it, kSecAttrAccount, CFBridgingRetain(key))
    }

    override suspend fun put(key: String, value: String?) {
        SecItemDelete(query(key))
        if (value == null) return
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val add = query(key)
        CFDictionaryAddValue(add, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        SecItemAdd(add, null)
    }

    override suspend fun get(key: String): String? = memScoped {
        val find = query(key)
        CFDictionaryAddValue(find, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(find, kSecMatchLimit, kSecMatchLimitOne)
        val found = alloc<CFTypeRefVar>()
        if (SecItemCopyMatching(find, found.ptr) != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(found.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }
}
