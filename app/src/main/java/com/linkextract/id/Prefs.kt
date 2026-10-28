package com.linkextract.id

import android.content.Context

/** 本地保存短剧版权中心的登录 Cookie */
object Prefs {

    private const val FILE = "id_extractor"
    private const val KEY_COOKIE = "sd_cookie"

    fun getCookie(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, null)
            ?.takeIf { it.isNotBlank() }

    fun setCookie(context: Context, cookie: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (cookie.isNullOrBlank()) remove(KEY_COOKIE) else putString(KEY_COOKIE, cookie)
            }
            .apply()
    }

    fun clearCookie(context: Context) = setCookie(context, null)
}
