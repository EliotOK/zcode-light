package com.eliotok.zcodelight

import android.content.Context

/** 本地小存储：远程链接只存在手机本地，不上传任何服务器。 */
object Prefs {
    private const val FILE = "zcode_lan"
    private const val KEY_URL = "remote_url"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    fun remoteUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_URL, "").orEmpty()

    fun setRemoteUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url.trim()).apply()
    }

    fun keepScreenOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
    }

    /** 粘贴的内容里只要带 zcode.z.ai/remote/ 就认为合法，缺协议头时自动补 https:// */
    fun normalizeRemoteUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http")) s = "https://$s"
        return if (s.contains("zcode.z.ai/remote/")) s else null
    }
}
