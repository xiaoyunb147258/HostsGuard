package com.acelite.hosts

import android.content.Context
import android.content.Intent

/**
 * 服务 → UI 的单向事件通道。
 *
 * 用普通广播 + setPackage 限定只发给本应用，
 * 不用 LocalBroadcastManager（已废弃，还要多一个依赖）。
 */
object Broadcast {

    const val ACTION_EVENT = "com.acelite.hosts.EVENT"

    const val KIND_LOG = "log"
    const val KIND_STATE = "state"
    const val KIND_STATS = "stats"

    const val TYPE_OK = "ok"
    const val TYPE_BLK = "blk"
    const val TYPE_BAD = "bad"

    fun push(ctx: Context, type: String, msg: String) {
        send(ctx, Intent(ACTION_EVENT)
            .putExtra("kind", KIND_LOG)
            .putExtra("type", type)
            .putExtra("msg", msg))
    }

    fun stateChanged(ctx: Context, running: Boolean) {
        send(ctx, Intent(ACTION_EVENT)
            .putExtra("kind", KIND_STATE)
            .putExtra("running", running))
    }

    fun statsChanged(ctx: Context) {
        send(ctx, Intent(ACTION_EVENT)
            .putExtra("kind", KIND_STATS)
            .putExtra("block", Prefs.statBlock(ctx))
            .putExtra("total", Prefs.statTotal(ctx)))
    }

    private fun send(ctx: Context, i: Intent) {
        try {
            val app = ctx.applicationContext
            i.setPackage(app.packageName)
            app.sendBroadcast(i)
        } catch (_: Exception) {}
    }
}