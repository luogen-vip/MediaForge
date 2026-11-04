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
}
