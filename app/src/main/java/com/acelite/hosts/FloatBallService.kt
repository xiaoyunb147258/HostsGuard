package com.acelite.hosts

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮球。
 *
 * 收起：56dp 圆球，可拖。
 * 展开：160dp 圆角面板 + 一个「开启/关闭拦截」按钮。
 * 状态与主界面通过广播同步。
 */
class FloatBallService : Service() {

    companion object {
        private const val BALL_DP = 56
        private const val PANEL_W_DP = 168
        private const val PANEL_H_DP = 64
    }

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var ball: View? = null
    private var panel: LinearLayout? = null
    private var btn: TextView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var expanded = false

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    // 拖动
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var moved = false

    // 状态同步
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Broadcast.ACTION_EVENT) return
            if (intent.getStringExtra("kind") == Broadcast.KIND_STATE) {
                val running = intent.getBooleanExtra("running", false)
                updateButton(running)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()

        val filter = IntentFilter(Broadcast.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }

        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        super.onDestroy()
    }

    // ══════════════════════════════════
    //  视图
    // ══════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        val container = FrameLayout(this)

        // ── 圆球 ──
        val b = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFC97995.toInt())
                setStroke(dp(2), 0x40FFFFFF)
            }
            elevation = dp(8)
        }
        val ballSize = dp(BALL_DP)
        container.addView(b, FrameLayout.LayoutParams(ballSize, ballSize))

        b.setOnTouchListener { _, ev -> onBallTouch(ev) }

        ball = b

        // ── 展开面板（初始隐藏） ──
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xF2FFF8FA.toInt())
                setStroke(dp(1), 0xFFF0DFE5.toInt())
            }
            elevation = dp(10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val t = TextView(this).apply {
            text = "开启拦截"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFC97995.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        t.setOnClickListener {
            val nowRunning = GuardVpnService.isRunning
            if (nowRunning) {
                // 停止
                startService(Intent(this, GuardVpnService::class.java).apply {
                    action = GuardVpnService.ACTION_STOP
                })
            } else {
                // 启动（悬浮球里无法弹 VPN 授权页，需已授权）
                val prep = android.net.VpnService.prepare(this)
                if (prep != null) {
                    // 未授权：提示回到应用
                    t.text = "请先在应用内授权"
                    t.postDelayed({ updateButton(GuardVpnService.isRunning) }, 2000)
                    return@setOnClickListener
                }
                startService(Intent(this, GuardVpnService::class.java).apply {
                    action = GuardVpnService.ACTION_START
                })
            }
        }

        p.addView(t, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 点面板空白处收起
        p.setOnClickListener { if (expanded) toggleExpand() }

        container.addView(p, FrameLayout.LayoutParams(dp(PANEL_W_DP), dp(PANEL_H_DP)))

        btn = t
        panel = p
        root = container
    }

    private fun showBall() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dp(BALL_DP), dp(BALL_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(BALL_DP) - dp(12)
            y = resources.displayMetrics.heightPixels / 3
        }

        lp = params

        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        updateButton(GuardVpnService.isRunning || Prefs.isProxyRunning(this))
    }

    // ══════════════════════════════════
    //  展开 / 收起
    // ══════════════════════════════════

    private fun toggleExpand() {
        if (moved) { moved = false; return }

        expanded = !expanded

        val p = lp ?: return
        if (expanded) {
            p.width = dp(PANEL_W_DP)
            p.height = dp(PANEL_H_DP)
            ball?.visibility = View.GONE
            panel?.visibility = View.VISIBLE
        } else {
            p.width = dp(BALL_DP)
            p.height = dp(BALL_DP)
            ball?.visibility = View.VISIBLE
            panel?.visibility = View.GONE
        }

        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
    }

    private fun updateButton(running: Boolean) {
        val t = btn ?: return
        if (running) {
            t.text = "关闭拦截"
            (t.background as? GradientDrawable)?.setColor(0xFF78A88E.toInt())
        } else {
            t.text = "开启拦截"
            (t.background as? GradientDrawable)?.setColor(0xFFC97995.toInt())
        }
    }

    // ══════════════════════════════════
    //  拖动
    // ══════════════════════════════════

    private fun onBallTouch(ev: MotionEvent): Boolean {
        val p = lp ?: return false

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = p.x
                startY = p.y
                dragging = true
                moved = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = (ev.rawX - downX).toInt()
                val dy = (ev.rawY - downY).toInt()

                if (!moved && (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4))) {
                    moved = true
                }

                if (moved) {
                    p.x = startX + dx
                    p.y = startY + dy
                    try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (moved) {
                    // 吸附到左/右边缘
                    snapToEdge(p)
                } else {
                    // 未移动 = 点击
                    toggleExpand()
                }
            }
        }
        return true
    }

    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val margin = dp(8)
        val w = if (expanded) dp(PANEL_W_DP) else dp(BALL_DP)

        p.x = if (p.x + w / 2 < screenW / 2) margin else screenW - w - margin

        // 限制竖直范围
        val screenH = resources.displayMetrics.heightPixels
        val h = if (expanded) dp(PANEL_H_DP) else dp(BALL_DP)
        if (p.y < 0) p.y = 0
        if (p.y > screenH - h) p.y = screenH - h

        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
    }
}