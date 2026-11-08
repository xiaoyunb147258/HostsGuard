package com.acelite.hosts

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量状态存储。
 * 只存开关、规则禁用集合、统计数字。
 */
object Prefs {

    private const val FILE = "hg_prefs"

    private const val K_RUNNING = "running"
    private const val K_FLOAT = "float_on"
    private const val K_BOOT = "boot_on"
    private const val K_BATTERY = "battery_asked"
    private const val K_DISABLED = "disabled_rules"
    private const val K_STAT_BLOCK = "stat_block"
    private const val K_STAT_TOTAL = "stat_total"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ── 开关 ──

    fun isProxyRunning(ctx: Context) = sp(ctx).getBoolean(K_RUNNING, false)
    fun setProxyRunning(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_RUNNING, v).apply()

    fun isFloatOn(ctx: Context) = sp(ctx).getBoolean(K_FLOAT, false)
    fun setFloatOn(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_FLOAT, v).apply()

    fun isBootOn(ctx: Context) = sp(ctx).getBoolean(K_BOOT, false)
    fun setBootOn(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_BOOT, v).apply()

    fun isBatteryAsked(ctx: Context) = sp(ctx).getBoolean(K_BATTERY, false)
    fun setBatteryAsked(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_BATTERY, v).apply()

    // ── 规则禁用集合 ──

    fun loadDisabled(ctx: Context): MutableSet<String> =
        (sp(ctx).getStringSet(K_DISABLED, null) ?: emptySet()).toMutableSet()

    fun saveDisabled(ctx: Context, set: Set<String>) =
        sp(ctx).edit().putStringSet(K_DISABLED, set).apply()

    // ── 统计 ──

    fun statBlock(ctx: Context) = sp(ctx).getLong(K_STAT_BLOCK, 0L)
    fun statTotal(ctx: Context) = sp(ctx).getLong(K_STAT_TOTAL, 0L)

    fun setStats(ctx: Context, block: Long, total: Long) {
        sp(ctx).edit()
            .putLong(K_STAT_BLOCK, block)
            .putLong(K_STAT_TOTAL, total)
            .apply()
    }

    fun bumpStats(ctx: Context, blocked: Boolean) {
        val e = sp(ctx).edit()
        e.putLong(K_STAT_TOTAL, statTotal(ctx) + 1)
        if (blocked) e.putLong(K_STAT_BLOCK, statBlock(ctx) + 1)
        e.apply()
    }

    fun resetStats(ctx: Context) =
        sp(ctx).edit()
            .putLong(K_STAT_BLOCK, 0L)
            .putLong(K_STAT_TOTAL, 0L)
            .apply()
}