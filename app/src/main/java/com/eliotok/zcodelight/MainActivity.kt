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
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf<Updater.UpdateUi>(Updater.UpdateUi.Hidden) }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            updateUi = Updater.UpdateUi.Checking
            val result = Updater.check()
            Prefs.setLastUpdateCheck(context, System.currentTimeMillis())
            updateUi = when (result) {
                is Updater.CheckResult.Available ->
                    Updater.UpdateUi.Available(result.release, result.expectedSha256)
                is Updater.CheckResult.UpToDate ->
                    if (manual) Updater.UpdateUi.UpToDate else Updater.UpdateUi.Hidden
                is Updater.CheckResult.Error ->
                    if (manual) Updater.UpdateUi.Error(result.message) else Updater.UpdateUi.Hidden
            }
        }
    }

    fun startDownload() {
        val current = updateUi as? Updater.UpdateUi.Available ?: return
        scope.launch {
            if (!Updater.canInstall(context)) {
                Updater.requestInstallPermission(context)
                updateUi = Updater.UpdateUi.Error(
                    "请在接下来的页面允许「安装未知应用」，返回后重新点「下载并安装」。"
                )
                return@launch
            }
            updateUi = Updater.UpdateUi.Progress(0)
            try {
                val file = Updater.downloadAndVerify(
                    context, current.release, current.expectedSha256,
                ) { percent -> updateUi = Updater.UpdateUi.Progress(percent) }
                Updater.launchInstall(context, file)
                updateUi = Updater.UpdateUi.Hidden
            } catch (e: Exception) {
                updateUi = Updater.UpdateUi.Error(e.message ?: "更新失败")
            }
        }
    }

    LaunchedEffect(Unit) {
        StateBus.remoteUrl.value = Prefs.remoteUrl(context)
        // 启动时静默检查一次更新（每 24 小时至多一次），有新版才弹窗
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(context) > 24 * 3_600_000L) {
            checkUpdate(manual = false)
        }
    }

    if (remoteUrl.isEmpty()) {
        PairScreen(
            onSave = { url ->
                Prefs.setRemoteUrl(context, url)
                StateBus.remoteUrl.value = url
            },
            onCheckUpdate = { checkUpdate(manual = true) },
        )
    } else {
        WebViewScreen(
            url = remoteUrl,
            status = status,
            onUpdateCheck = { checkUpdate(manual = true) },
            onClearLink = {
                Prefs.setRemoteUrl(context, "")
                StateBus.remoteUrl.value = ""
                context.stopService(Intent(context, KeepAliveService::class.java))
            },
        )
    }

    UpdateDialog(
        ui = updateUi,
        onDismiss = { updateUi = Updater.UpdateUi.Hidden },
        onDownload = { startDownload() },
    )
}

@Composable
private fun PairScreen(onSave: (String) -> Unit, onCheckUpdate: () -> Unit) {
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
            Text("检查更新（当前 v${BuildConfig.VERSION_NAME}）")
        }
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
private fun WebViewScreen(
    url: String,
    status: String,
    onUpdateCheck: () -> Unit,
    onClearLink: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val recreateTick by StateBus.recreateTick.collectAsState()
    var keepOn by remember { mutableStateOf(Prefs.keepScreenOn(context)) }
    var progress by remember { mutableIntStateOf(100) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingFilePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // 官方页面的文件/图片上传依赖 <input type="file">，由系统选择器承接
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFilePathCallback
        pendingFilePathCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }
    val onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit =
        { callback, params ->
            pendingFilePathCallback?.onReceiveValue(null)
            pendingFilePathCallback = callback
            try {
                fileChooserLauncher.launch(params.createIntent())
            } catch (_: ActivityNotFoundException) {
                pendingFilePathCallback = null
                callback.onReceiveValue(null)
            }
        }

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
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("检查更新") },
                    onClick = { menuOpen = false; onUpdateCheck() },
                )
                DropdownMenuItem(
                    text = { Text("换链接") },
                    onClick = { menuOpen = false; onClearLink() },
                )
            }
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
                    createWebView(
                        ctx = ctx,
                        url = url,
                        onProgress = { progress = it },
                        onFileChooser = onFileChooser,
                    ).also { webViewRef.value = it }
                },
            )
        }
    }

    BackHandler {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else activity?.finish()
    }
}

@Composable
private fun UpdateDialog(
    ui: Updater.UpdateUi,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    when (ui) {
        is Updater.UpdateUi.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("检查更新") },
            text = { Text("正在检查新版本…") },
            confirmButton = {},
        )
        is Updater.UpdateUi.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 v${ui.release.version}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!ui.release.notes.isNullOrBlank()) {
                        Text(ui.release.notes.take(500))
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        if (ui.expectedSha256 != null) "已获取 SHA-256 校验值，安装前会校验完整性。"
                        else "该版本未提供校验文件，将只校验包名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDownload) { Text("下载并安装") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )
        is Updater.UpdateUi.Progress -> AlertDialog(
            onDismissRequest = { /* 下载中不允许关闭，避免半途误触 */ },
            title = { Text("正在下载更新") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { ui.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${ui.percent}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is Updater.UpdateUi.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新") },
            text = { Text(ui.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
        )
        is Updater.UpdateUi.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("已是最新版本") },
            text = { Text("当前 v${BuildConfig.VERSION_NAME} 已是最新。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("好的") } },
        )
        Updater.UpdateUi.Hidden -> {}
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    ctx: Context,
    url: String,
    onProgress: (Int) -> Unit,
    onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
): WebView {
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

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                onFileChooser(filePathCallback, fileChooserParams)
                return true
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
