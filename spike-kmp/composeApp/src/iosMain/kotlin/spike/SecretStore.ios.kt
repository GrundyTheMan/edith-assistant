@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package spike

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
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val SERVICE = "edith-kmp-spike"

/** A query for one item of ours, by name. (Nothing here is released: a spike.) */
private fun query(key: String) = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr).also {
    CFDictionaryAddValue(it, kSecClass, kSecClassGenericPassword)
    CFDictionaryAddValue(it, kSecAttrService, CFBridgingRetain(SERVICE))
    CFDictionaryAddValue(it, kSecAttrAccount, CFBridgingRetain(key))
}

actual fun secretPut(key: String, value: String): Boolean {
    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
    SecItemDelete(query(key))
    val add = query(key)
    CFDictionaryAddValue(add, kSecValueData, CFBridgingRetain(data))
    return SecItemAdd(add, null) == errSecSuccess
}

actual fun secretGet(key: String): String? = memScoped {
    val find = query(key)
    CFDictionaryAddValue(find, kSecReturnData, kCFBooleanTrue)
    CFDictionaryAddValue(find, kSecMatchLimit, kSecMatchLimitOne)
    val found = alloc<CFTypeRefVar>()
    if (SecItemCopyMatching(find, found.ptr) != errSecSuccess) return@memScoped null
    val data = CFBridgingRelease(found.value) as? NSData ?: return@memScoped null
    NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
}
