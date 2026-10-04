package com.eliotok.zcodelight

import android.webkit.WebView

/** 让前台服务能摸到当前承载官方远程页面的 WebView 实例。 */
object WebViewHolder {
    @Volatile
    var webView: WebView? = null

    @Volatile
    var lastLoaded: String? = null

    fun reset() {
        webView = null
        lastLoaded = null
    }
}
