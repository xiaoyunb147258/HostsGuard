package com.acelite.hosts

/**
 * 最小 DNS 报文处理。
 *
 * 只做两件事：
 * 1. 从查询包里取出 QNAME
 * 2. 对查询包生成一个「应答 0.0.0.0」的回复
 *
 * 不支持压缩指针之外的复杂格式 —— 但 QNAME 解析必须支持压缩（虽然查询里极少出现）。
 */
object DnsPacket {

    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    /**
     * 从 DNS 查询报文里取出域名。
     * 失败返回 null。
     */
    fun queryName(pkt: ByteArray): String? {
        if (pkt.size < 12) return null

        val flags = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return null          // QR=1 → 是响应，跳过
        if ((flags and 0x7800) != 0) return null          // Opcode != QUERY，跳过

        val qdCount = ((pkt[4].toInt() and 0xFF) shl 8) or (pkt[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        return parseQName(pkt, 12)?.first
    }

    /**
     * 解析 QNAME，返回 (域名, 结束偏移)。
     * 支持压缩指针。
     */
    private fun parseQName(pkt: ByteArray, start: Int): Pair<String, Int>? {
        val sb = StringBuilder(64)
        var i = start
        var jumped = false
        var endOffset = -1
        var safety = 0

        while (i < pkt.size) {
            if (safety++ > 64) return null               // 防死循环

            val b = pkt[i].toInt() and 0xFF

            if (b == 0) {
                i += 1
                if (!jumped) endOffset = i
                break
            }

            if ((b and 0xC0) == 0xC0) {                  // 压缩指针
                if (i + 1 >= pkt.size) return null
                val ptr = ((b and 0x3F) shl 8) or (pkt[i + 1].toInt() and 0xFF)
                if (!jumped) endOffset = i + 2
                jumped = true
                if (ptr >= pkt.size) return null
                i = ptr
                continue
            }

            if ((b and 0xC0) != 0) return null           // 不支持扩展标签

            val labelLen = b
            if (labelLen > 63) return null
            if (i + 1 + labelLen > pkt.size) return null

            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until labelLen) {
                val c = pkt[i + 1 + k].toInt() and 0xFF
                sb.append(if (c in 0x21..0x7E) c.toChar() else '_')
            }

            i += 1 + labelLen
        }

        if (endOffset < 0) endOffset = i
        if (sb.isEmpty()) return null

        return sb.toString().lowercase() to endOffset
    }

    /**
     * 构造「域名解析到 0.0.0.0」的应答。
     *
     * 从原查询复制：
     *   - 事务 ID（2B）
     *   - 问题段（QNAME + QTYPE + QCLASS）
     * 添加一个 A 记录指向 0.0.0.0。
     */
    fun buildBlockedReply(query: ByteArray): ByteArray? {
        if (query.size < 12) return null

        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        // 找到问题段结束位置
        val parsed = parseQName(query, 12) ?: return null
        val qnameEnd = parsed.second

        // QTYPE(2) + QCLASS(2)
        if (qnameEnd + 4 > query.size) return null
        val questionEnd = qnameEnd + 4

        // 新报文 = 12 头部 + 问题段 + 16 应答记录
        val answerLen = 16
        val out = ByteArray(questionEnd + answerLen)

        // ── 头部 12 字节 ──
        System.arraycopy(query, 0, out, 0, 2)            // ID

        // flags: QR=1, Opcode=0, AA=0, TC=0, RD=沿用, RA=1, RCODE=0
        val rdBit = if ((query[2].toInt() and 0x01) != 0) 0x0100 else 0
        val flags = 0x8180 or rdBit
        out[2] = ((flags ushr 8) and 0xFF).toByte()
        out[3] = (flags and 0xFF).toByte()

        out[4] = 0                                        // QDCOUNT 高
        out[5] = 1                                        // QDCOUNT 低
        out[6] = 0                                        // ANCOUNT 高
        out[7] = 1                                        // ANCOUNT 低（1 条应答）
        out[8] = 0                                        // NSCOUNT
        out[9] = 0
        out[10] = 0                                       // ARCOUNT
        out[11] = 0

        // ── 问题段：原样复制 ──
        System.arraycopy(query, 12, out, 12, questionEnd - 12)

        // ── 应答记录 16 字节 ──
        var p = questionEnd

        // NAME：指向偏移 12 的压缩指针
        out[p++] = 0xC0.toByte()
        out[p++] = 0x0C

        // TYPE = A
        out[p++] = 0
        out[p++] = TYPE_A.toByte()

        // CLASS = IN
        out[p++] = 0
        out[p++] = CLASS_IN.toByte()

        // TTL = 60 秒
        out[p++] = 0
        out[p++] = 0
        out[p++] = 0
        out[p++] = 60

        // RDLENGTH = 4
        out[p++] = 0
        out[p++] = 4

        // RDATA = 0.0.0.0
        out[p++] = 0
        out[p++] = 0
        out[p++] = 0
        out[p] = 0

        return out
    }
}