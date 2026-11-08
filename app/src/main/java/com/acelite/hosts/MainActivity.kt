package com.acelite.hosts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendingStartAfterPermission = false

    companion object {
        private const val REQ_VPN = 1001
    }

    // ── 广播接收 ──

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Broadcast.ACTION_EVENT) return
            when (intent.getStringExtra("kind")) {

                Broadcast.KIND_LOG -> {
                    val type = intent.getStringExtra("type") ?: "ok"
                    val msg = intent.getStringExtra("msg") ?: ""
                    js("UI.addLog(${q(type)}, ${q(msg)})")
                }

                Broadcast.KIND_STATE -> {
                    val running = intent.getBooleanExtra("running", false)
                    js("UI.setRunning($running)")
                    refreshStats()
                }

                Broadcast.KIND_STATS -> refreshStats()
            }
        }
    }

    // ── 生命周期 ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
            }
            setBackgroundColor(0xFFFFF8FA.toInt())
            overScrollMode = WebView.OVER_SCROLL_NEVER

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pushAllState()
                }
            }

            addJavascriptInterface(Bridge(), "Android")

            loadUrl("file:///android_asset/index.html")
        }

        setContentView(web)
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter(Broadcast.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }

        // 悬浮窗权限状态可能变了
        pushOverlayState()

        if (pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            // 从权限页返回时已授权，直接起服务，不要再走 prepare
            if (VpnService.prepare(this) == null) startVpnNow()
        }
    }

    override fun onPause() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpnNow()
            } else {
                js("UI.error(${q("用户拒绝了 VPN 授权")})")
            }
        }
    }

    // ══════════════════════════════════════
    //  JS 桥
    // ══════════════════════════════════════

    inner class Bridge {

        @JavascriptInterface
        fun post(json: String) {
            try {
                val obj = JSONObject(json)
                val action = obj.optString("action")
                val data = obj.optJSONObject("data") ?: JSONObject()

                runOnUiThread { handle(action, data) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handle(action: String, data: JSONObject) {
        when (action) {

            "ready" -> pushAllState()

            "start" -> requestStart()

            "stop" -> stopVpn()

            "reload" -> {
                js("UI.setBusy(true)")
                val rules = Rules.load(this)
                pushRules(rules)
                js("UI.setBusy(false)")
                js("UI.addLog('ok', ${q("规则已重载 · ${rules.enabledCount()} 条")})")
                toast("规则已重载")
            }

            "resetStats" -> {
                Prefs.resetStats(this)
                refreshStats()
            }

            "floatWindow" -> {
                val on = data.optBoolean("on", false)
                setFloat(on)
            }

            "askOverlay" -> askOverlayPermission()

            "copy" -> {
                val text = data.optString("text")
                if (text.isNotEmpty()) {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("channel", text))
                    toast("已复制 $text")
                }
            }

            "tab" -> { /* 前端自己处理，后端不需要 */ }
        }
    }

    // ══════════════════════════════════════
    //  VPN 控制
    // ══════════════════════════════════════

    private fun requestStart() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            pendingStartAfterPermission = true
            try {
                startActivityForResult(prep, REQ_VPN)
            } catch (e: Exception) {
                js("UI.error(${q("无法打开 VPN 授权页")})")
            }
            return
        }
        startVpnNow()
    }

    private fun startVpnNow() {
        js("UI.setBusy(true)")
        val i = Intent(this, GuardVpnService::class.java).apply {
            action = GuardVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
        // 状态由服务通过广播回传
        web.postDelayed({ if (!GuardVpnService.isRunning) js("UI.setBusy(false)") }, 1500)
    }

    private fun stopVpn() {
        val i = Intent(this, GuardVpnService::class.java).apply {
            action = GuardVpnService.ACTION_STOP
        }
        startService(i)
        refreshStats()
    }

    // ══════════════════════════════════════
    //  悬浮窗
    // ══════════════════════════════════════

    private fun askOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Prefs.setFloatOn(this, false)
            pushOverlayState()
            return
        }

        if (Settings.canDrawOverlays(this)) {
            pushOverlayState()
            toast("悬浮窗权限已授予")
            return
        }

        try {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        } catch (e: Exception) {
            toast("请手动在系统设置中授予悬浮窗权限")
        }
    }

    private fun setFloat(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            askOverlayPermission()
            Prefs.setFloatOn(this, false)
            js("UI.setFloat(false)")
            return
        }

        Prefs.setFloatOn(this, on)

        val i = Intent(this, FloatBallService::class.java)
        if (on) startService(i) else stopService(i)

        js("UI.setFloat($on)")
    }

    // ══════════════════════════════════════
    //  状态推送
    // ══════════════════════════════════════

    private fun pushAllState() {
        pushRules(Rules.load(this))

        val running = GuardVpnService.isRunning || Prefs.isProxyRunning(this)
        js("UI.setRunning($running)")

        refreshStats()
        pushOverlayState()

        val float = Prefs.isFloatOn(this)
        js("UI.setFloat($float)")
    }

    private fun pushRules(rules: Rules) {
        val text = try {
            assets.open(Rules.ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        js("UI.setRules(${q(text)}, ${q("内置规则 · ${rules.enabledCount()} 条")})")
    }

    private fun refreshStats() {
        val block = Prefs.statBlock(this)
        val total = Prefs.statTotal(this)
        js("UI.setStats({block:$block, total:$total})")
    }

    private fun pushOverlayState() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else false
        js("UI.setOverlayGranted($granted)")
    }

    // ══════════════════════════════════════
    //  工具
    // ══════════════════════════════════════

    private fun js(code: String) {
        web.post {
            try { web.evaluateJavascript(code, null) } catch (_: Exception) {}
        }
    }

    /** JS 字符串字面量转义 */
    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}