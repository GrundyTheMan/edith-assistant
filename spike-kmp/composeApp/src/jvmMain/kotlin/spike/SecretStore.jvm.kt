package spike

private val secrets = mutableMapOf<String, String>()

actual fun secretPut(key: String, value: String): Boolean {
    secrets[key] = value
    return true
}

actual fun secretGet(key: String): String? = secrets[key]
