package com.logan.mediaforge.core

import android.content.Context

/** 每个类别的默认保存位置（SAF 文件夹树 URI 或 null=默认下载文件夹） */
object SettingsRepo {
    const val CAT_AUDIO = "audio"
    const val CAT_IMAGE = "image"
    const val CAT_FILE = "file"

    private fun prefs(context: Context) =
        context.getSharedPreferences("mediaforge_settings", Context.MODE_PRIVATE)

    fun folder(context: Context, key: String): String? = prefs(context).getString(key, null)

    fun setFolder(context: Context, key: String, value: String?) {
        prefs(context).edit().putString(key, value).apply()
    }

    // ---- 记住当前页签：进程被系统回收后重新打开，直接回到刚才的页面（不回首页） ----
    private const val KEY_LAST_TAB = "last_tab"

    fun lastTab(context: Context): String? = prefs(context).getString(KEY_LAST_TAB, null)

    fun setLastTab(context: Context, route: String) {
        prefs(context).edit().putString(KEY_LAST_TAB, route).apply()
    }
}
