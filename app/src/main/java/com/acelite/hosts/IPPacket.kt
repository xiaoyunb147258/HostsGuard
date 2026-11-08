package com.acelite.hosts

/**
 * 极简 IPv4 + UDP 解析 / 重组。
 * 只处理 IPv4 + UDP + 不加密分片 + 无 IP 选项。
 */
class IPPacket private constructor(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
    val protocol: Int
) {

    /**
     * 用新的 UDP 载荷构造回包：
     * 源/目的地址、端口全部对调。
     */
    fun buildReply(newPayload: ByteArray): ByteArray {
        val udpLen = 8 + newPayload.size
        val total = 20 + udpLen
        val pkt = ByteArray(total)

        // ── IPv4 头 ──
        pkt[0] = 0x45                       // v4, IHL=5
        pkt[1] = 0
        putU16(pkt, 2, total)               // total length
        putU16(pkt, 4, 0)                   // ID
        putU16(pkt, 6, 0x4000)              // DF
        pkt[8] = 64                         // TTL
        pkt[9] = PROTO_UDP.toByte()
        putU16(pkt, 10, 0)                  // checksum 占位

        // 源 = 原 dst（本机 VPN 地址），目的 = 原 src
        System.arraycopy(dstIp, 0, pkt, 12, 4)
        System.arraycopy(srcIp, 0, pkt, 16, 4)

        putU16(pkt, 10, ipChecksum(pkt, 0, 20))

        // ── UDP 头 ──
        val udpOff = 20
        putU16(pkt, udpOff, dstPort)        // 源 = 原目的（53）
        putU16(pkt, udpOff + 2, srcPort)    // 目的 = 原源
        putU16(pkt, udpOff + 4, udpLen)
        putU16(pkt, udpOff + 6, 0)          // checksum 占位

        System.arraycopy(newPayload, 0, pkt, udpOff + 8, newPayload.size)

        // UDP 校验和（含 IPv4 伪头部）
        putU16(pkt, udpOff + 6, udpChecksum(pkt, udpLen, udpOff))

        return pkt
    }

    companion object {

        const val PROTO_UDP = 17

        /**
         * 解析 IP 包。不满足条件返回 null。
         */
        fun parse(pkt: ByteArray, len: Int): IPPacket? {
            if (len < 28) return null                       // IP 20 + UDP 8

            val vihl = pkt[0].toInt() and 0xFF
            if ((vihl ushr 4) != 4) return null             // 非 IPv4

            val ihl = (vihl and 0x0F) * 4
            if (ihl < 20 || ihl + 8 > len) return null      // 有选项 / 太短

            if ((pkt[9].toInt() and 0xFF) != PROTO_UDP) return null

            // 分片：MF 位或 offset != 0 都跳过
            val frag = getU16(pkt, 6)
            if ((frag and 0x2000) != 0 || (frag and 0x1FFF) != 0) return null

            val srcIp = pkt.copyOfRange(12, 16)
            val dstIp = pkt.copyOfRange(16, 20)

            val srcPort = getU16(pkt, ihl)
            val dstPort = getU16(pkt, ihl + 2)

            val udpLen = getU16(pkt, ihl + 4)
            if (udpLen < 8) return null

            val start = ihl + 8
            val end = minOf(len, ihl + udpLen)
            if (end <= start) return null

            return IPPacket(
                srcIp, dstIp,
                srcPort, dstPort,
                pkt.copyOfRange(start, end),
                PROTO_UDP
            )
        }

        // ── 工具 ──

        fun putU16(a: ByteArray, off: Int, v: Int) {
            a[off] = ((v ushr 8) and 0xFF).toByte()
            a[off + 1] = (v and 0xFF).toByte()
        }

        fun getU16(a: ByteArray, off: Int): Int =
            ((a[off].toInt() and 0xFF) shl 8) or (a[off + 1].toInt() and 0xFF)

        /** IP 头校验和 */
        private fun ipChecksum(a: ByteArray, off: Int, len: Int): Int {
            var sum = 0L
            var i = off
            val end = off + len
            while (i + 1 < end) {
                sum += getU16(a, i).toLong()
                i += 2
            }
            if (i < end) sum += ((a[i].toInt() and 0xFF) shl 8).toLong()
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }

        /**
         * UDP 校验和：伪头部（12B）+ UDP 头 + 载荷。
         * 在整包 pkt 的第 udpOff 处。
         */
        private fun udpChecksum(pkt: ByteArray, udpLen: Int, udpOff: Int): Int {
            var sum = 0L

            // 伪头部：src IP (4) + dst IP (4) + zero(1) + proto(1) + UDP len(2)
            sum += getU16(pkt, 12).toLong()
            sum += getU16(pkt, 14).toLong()
            sum += getU16(pkt, 16).toLong()
            sum += getU16(pkt, 18).toLong()
            sum += PROTO_UDP.toLong()
            sum += udpLen.toLong()

            // UDP 头 + 载荷（校验和字段本身按 0 计算）
            var i = udpOff
            val end = udpOff + udpLen
            while (i + 1 < end) {
                if (i == udpOff + 6) {          // 跳过校验和字段
                    i += 2
                    continue
                }
                sum += getU16(pkt, i).toLong()
                i += 2
            }
            if (i < end) sum += ((pkt[i].toInt() and 0xFF) shl 8).toLong()

            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)

            val cs = (sum.inv() and 0xFFFF).toInt()
            // UDP 校验和算出 0 时要写成 0xFFFF
            return if (cs == 0) 0xFFFF else cs
        }
    }
}