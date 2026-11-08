package com.acelite.hosts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 本地 DNS 过滤 VPN。
 *
 * 只接管 UDP:53（DNS）。
 * 命中规则 → 回复 0.0.0.0
 * 未命中   → 转发上游 DNS
 * 其他流量 → 不经过此 VPN（通过 addDisallowedApplication 排除 + 只路由 53）
 */
class GuardVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.acelite.hosts.START"
        const val ACTION_STOP = "com.acelite.hosts.STOP"

        const val CHANNEL_ID = "hg_status"
        const val NOTIF_ID = 1001

        const val VPN_ADDR = "10.66.66.1"
        const val VPN_PREFIX = 32
        const val DNS_PORT = 53

        /** 上游 DNS */
        val UPSTREAMS = listOf(
            "223.5.5.5",      // 阿里
            "119.29.29.29"    // 腾讯
        )

        @Volatile
        var isRunning = false
    }

    private var scope: CoroutineScope? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Job? = null
    private var statsWorker: Job? = null
    private var rules: Rules = Rules.parse("")
    private var upstream: DatagramSocket? = null
    private val upstreamAddr = InetSocketAddress(InetAddress.getByName(UPSTREAMS[0]), 53)

    /** 内存计数，每 2 秒落盘一次 */
    private var blockedCount = 0
    private var totalCount = 0
    private var lastLogAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        val builder = Builder()
            .setSession("Hosts Guard")
            .addAddress(VPN_ADDR, VPN_PREFIX)
            .addDnsServer(VPN_ADDR)
            .addRoute(VPN_ADDR, VPN_PREFIX)

        // 不把本应用自己塞进隧道，避免上游 DNS 请求再被自己接住
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (fd == null) {
            Prefs.setProxyRunning(this, false)
            Broadcast.push(this, "error", "VPN 建立失败")
            stopSelf()
            return
        }

        tunnel = fd
        isRunning = true
        Prefs.setProxyRunning(this, true)

        rules = Rules.load(this)
        startForeground(NOTIF_ID, buildNotification())

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        upstream = try {
            DatagramSocket().apply {
                soTimeout = 4000
                connect(upstreamAddr)
            }
        } catch (e: Exception) {
            null
        }

        Broadcast.push(this, "ok", "防护已启动 · ${rules.enabledCount()} 条规则")
        Broadcast.stateChanged(this, true)

        worker = scope?.launch { tunnelLoop(fd) }
        statsWorker = scope?.launch { statsLoop() }
    }

    private fun stopVpn() {
        isRunning = false
        Prefs.setProxyRunning(this, false)

        // 停止前把剩余计数落盘
        flushStats()

        worker?.cancel()
        worker = null
        statsWorker?.cancel()
        statsWorker = null

        try { upstream?.close() } catch (_: Exception) {}
        upstream = null

        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null

        scope?.cancel()
        scope = null

        Broadcast.push(this, "ok", "防护已停止")
        Broadcast.stateChanged(this, false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 主循环：读 VPN 网卡 → 解 DNS 包 → 查规则 → 回包或转发
     */
    private suspend fun tunnelLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)

        val buf = ByteArray(4096)

        while (scope?.isActive == true && isRunning) {
            val len: Int = try {
                input.read(buf)
            } catch (e: Exception) {
                if (!isRunning) break
                delay(50)
                continue
            }

            if (len <= 0) {
                delay(20)
                continue
            }

            try {
                handlePacket(buf, len, output)
            } catch (e: Exception) {
                // 单个包出错不能影响主循环
            }
        }

        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    /**
     * 处理一个 IP 包。
     * 只处理 IPv4 + UDP + 目标端口 53，其余丢弃。
     */
    private fun handlePacket(pkt: ByteArray, len: Int, out: FileOutputStream) {
        val parsed = IPPacket.parse(pkt, len) ?: return
        if (parsed.dstPort != DNS_PORT) return

        val query = parsed.payload
        val qname = DnsPacket.queryName(query) ?: return

        val blocked = rules.shouldBlock(qname)

        totalCount++
        if (blocked) blockedCount++

        if (blocked) {
            val answer = DnsPacket.buildBlockedReply(query)
            if (answer != null) {
                val reply = parsed.buildReply(answer)
                out.write(reply, 0, reply.size)
                out.flush()
            }
            // 日志也节流，不然 UI 会淹没
            if (System.currentTimeMillis() - lastLogAt > 120) {
                lastLogAt = System.currentTimeMillis()
                Broadcast.push(this, Broadcast.TYPE_BLK, "拦截  $qname")
            }
            return
        }

        // 未命中 → 转发上游
        val sock = upstream ?: return
        try {
            sock.send(DatagramPacket(query, query.size, upstreamAddr))
            val rbuf = ByteArray(1500)
            val rpkt = DatagramPacket(rbuf, rbuf.size)
            sock.receive(rpkt)
            val ans = rbuf.copyOf(rpkt.length)
            val reply = parsed.buildReply(ans)
            out.write(reply, 0, reply.size)
            out.flush()
        } catch (_: Exception) {
        }
    }

    /** 每 2 秒把内存计数落盘 + 通知 UI */
    private suspend fun statsLoop() {
        while (scope?.isActive == true && isRunning) {
            delay(2000)
            flushStats()
        }
    }

    private fun flushStats() {
        try {
            val baseB = Prefs.statBlock(this)
            val baseT = Prefs.statTotal(this)
            Prefs.setStats(
                this,
                baseB + blockedCount,
                baseT + totalCount
            )
            blockedCount = 0
            totalCount = 0
            Broadcast.statsChanged(this)
        } catch (_: Exception) {}
    }

    // ── 前台通知 ──

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = getString(R.string.channel_desc)
                        setShowBadge(false)
                    }
                )
            }
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("防护运行中 · ${rules.enabledCount()} 条规则")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        worker?.cancel()
        try { upstream?.close() } catch (_: Exception) {}
        try { tunnel?.close() } catch (_: Exception) {}
        scope?.cancel()
        super.onDestroy()
    }
}