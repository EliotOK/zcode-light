package com.eliotok.zcodelight

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        requestNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /** 支持两种配对方式：系统里直接点开远程链接，或把链接文本“分享”给本应用。 */
    private fun handleIncomingIntent(intent: Intent?) {
        val candidate = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        Prefs.normalizeRemoteUrl(candidate)?.let {
            Prefs.setRemoteUrl(this, it)
            StateBus.remoteUrl.value = it
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val remoteUrl by StateBus.remoteUrl.collectAsState()
    val status by StateBus.status.collectAsState()

    LaunchedEffect(Unit) {
        StateBus.remoteUrl.value = Prefs.remoteUrl(context)
    }

    if (remoteUrl.isEmpty()) {
        PairScreen(onSave = { url ->
            Prefs.setRemoteUrl(context, url)
            StateBus.remoteUrl.value = url
        })
    } else {
        WebViewScreen(url = remoteUrl, status = status)
    }
}

@Composable
private fun PairScreen(onSave: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ZCode Light", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "把电脑端 ZCode「远程控制」展示的链接粘贴到这里，" +
                "一次配对，之后由本应用托管官方页面并保持常连。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; invalid = false },
            label = { Text("https://zcode.z.ai/remote/…") },
            isError = invalid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (invalid) {
            Text(
                "没有识别到 zcode.z.ai/remote/ 链接",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val normalized = Prefs.normalizeRemoteUrl(text)
                if (normalized == null) invalid = true else onSave(normalized)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("配对并连接") }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { requestIgnoreBatteryOptimization(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("申请忽略电池优化（建议）") }
        Spacer(Modifier.height(24.dp))
        Text(
            "链接只保存在本机，不会上传；桌面端重新生成配对链接后，在这里更新即可。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}

private fun requestIgnoreBatteryOptimization(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.packageName),
            )
        )
    } catch (_: ActivityNotFoundException) {
        // 部分厂商 ROM 不支持该入口，忽略即可
    }
}

@Composable
private fun WebViewScreen(url: String, status: String) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val recreateTick by StateBus.recreateTick.collectAsState()
    var keepOn by remember { mutableStateOf(Prefs.keepScreenOn(context)) }
    var progress by remember { mutableIntStateOf(100) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) { KeepAliveService.start(context) }

    LaunchedEffect(keepOn) {
        Prefs.setKeepScreenOn(context, keepOn)
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("息屏常亮", style = MaterialTheme.typography.labelMedium)
            Switch(checked = keepOn, onCheckedChange = { keepOn = it })
            OutlinedButton(onClick = {
                Prefs.setRemoteUrl(context, "")
                StateBus.remoteUrl.value = ""
                context.stopService(Intent(context, KeepAliveService::class.java))
            }) { Text("换链接") }
        }
        if (progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // recreateTick 变化（渲染进程崩溃）时强制重建 WebView
        key(recreateTick) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    createWebView(ctx, url, onProgress = { progress = it })
                        .also { webViewRef.value = it }
                },
            )
        }
    }

    BackHandler {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else activity?.finish()
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(ctx: Context, url: String, onProgress: (Int) -> Unit): WebView {
    return WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = true

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host == "zcode.z.ai" || host.endsWith(".z.ai")) return false
                // 站外链接交给系统浏览器，避免把官方页面“导航走”
                return try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                WebViewHolder.reset()
                StateBus.recreateTick.value++
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }

            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult,
            ): Boolean {
                AlertDialog.Builder(ctx)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult,
            ): Boolean {
                AlertDialog.Builder(ctx)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }

        loadUrl(url)
        WebViewHolder.webView = this
        WebViewHolder.lastLoaded = url
    }
}
