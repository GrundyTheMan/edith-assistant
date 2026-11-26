package com.edith.app.settings

/** Which hosts a platform lets the app reach over plain `http://` (not `https://`); [problem] is why a host is refused, or null when it is allowed. */
fun interface PlainHttpRule {
    fun problem(host: String): String?
}

/**
 * The iPhone's rule (D49, decided 2026-11-26): App Transport Security allows plain HTTP only to the home network (`NSAllowsLocalNetworking`: private
 * addresses, `.local` names and names with no dot) and to the owner's Tailscale names (`ts.net`, an exception in `iosApp/project.yml`). A Tailscale number
 * (100.x) is not a local network to iOS, so it is refused here with the way out instead of failing later as an unreachable server. Keep this in step with
 * the plist.
 */
object AtsPlainHttpRule : PlainHttpRule {
    override fun problem(host: String): String? {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || '.' !in h && !h.all { it.isDigit() }) return null
        if (h == "ts.net" || h.endsWith(".ts.net")) return null
        val octets = h.split('.').map { it.toIntOrNull() ?: -1 }
        if (octets.size == 4 && octets.all { it in 0..255 }) {
            val (a, b) = octets
            if (a == 100 && b in 64..127) {
                return "iPhones block plain http:// to a Tailscale number (100.x). Use your PC's Tailscale name instead, the one ending in .ts.net, e.g. http://my-pc.tail1234.ts.net:11434."
            }
            if (a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)) return null
        }
        return "iPhones only allow plain http:// to your home network or to your PC's Tailscale name (ending in .ts.net). Use https://, or one of those."
    }
}
