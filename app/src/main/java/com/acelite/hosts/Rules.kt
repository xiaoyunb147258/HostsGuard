package com.acelite.hosts

import android.content.Context

/**
 * 规则集。
 *
 * 内置规则放在 assets/host.txt，格式：`0.0.0.0 域名`
 * 启动时解析成 HashSet，匹配 O(1)。
 *
 * 支持通配：条目 `*.qq.com` 会匹配任意子域。
 */
class Rules private constructor(
    val entries: List<Entry>
) {

    /** 一条规则 */
    data class Entry(
        val domain: String,
        val enabled: Boolean
    )

    /** 精确域名集合（不含 *） */
    private val exact: HashSet<String> = HashSet(entries.size * 2)

    /** 通配后缀集合，存 `*.qq.com` 的 `qq.com` */
    private val suffixes: HashSet<String> = HashSet()

    init {
        for (e in entries) {
            if (!e.enabled) continue
            val d = e.domain
            if (d.startsWith("*.")) {
                suffixes.add(d.substring(2))
            } else {
                exact.add(d)
            }
        }
    }

    /**
     * 判断域名是否应被拦截。
     * 先查精确，再逐级向上查通配后缀。
     */
    fun shouldBlock(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false

        // 去掉结尾的点（FQDN 写法）
        val h = if (host.endsWith(".")) host.dropLast(1).lowercase() else host.lowercase()

        if (exact.contains(h)) return true
        if (suffixes.isEmpty()) return false

        // 逐级裁掉最左段，查通配
        var i = h.indexOf('.')
        while (i in 0 until h.length - 1) {
            if (suffixes.contains(h.substring(i + 1))) return true
            i = h.indexOf('.', i + 1)
        }
        return false
    }

    fun enabledCount(): Int = exact.size + suffixes.size

    companion object {

        const val ASSET_NAME = "host.txt"

        /** 从 assets 读取并解析 */
        fun load(ctx: Context): Rules {
            val disabled = Prefs.loadDisabled(ctx)
            return parse(readAsset(ctx), disabled)
        }

        /** 纯解析，测试用 */
        fun parse(text: String, disabled: Set<String> = emptySet()): Rules {
            val out = ArrayList<Entry>(256)
            val seen = HashSet<String>(256)

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) continue

                val ip = parts[0]
                if (!isIpv4(ip)) continue

                val domain = parts[1].lowercase()
                if (!isValidDomain(domain)) continue

                if (!seen.add(domain)) continue

                out.add(Entry(domain, domain !in disabled))
            }
            return Rules(out)
        }

        private fun readAsset(ctx: Context): String = try {
            ctx.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        private fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            if (parts.size != 4) return false
            for (p in parts) {
                if (p.isEmpty() || p.length > 3) return false
                val n = p.toIntOrNull() ?: return false
                if (n !in 0..255) return false
            }
            return true
        }

        /** 域名合法性：至少一个点，TLD 纯字母且 >=2 位 */
        private fun isValidDomain(s: String): Boolean {
            if (!s.contains('.')) return false
            if (s.contains(' ')) return false
            val body = if (s.startsWith("*.")) s.substring(2) else s
            if (body.isEmpty()) return false
            val tld = body.substringAfterLast('.')
            return tld.length >= 2 && tld.all { it in 'a'..'z' }
        }
    }
}