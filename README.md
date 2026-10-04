# ZCode Light

让手机稳定连接电脑端 [ZCode](https://zcode.z.ai)「远程控制」的原生 Android 客户端。与你项目里 Codex Light（codex-lan）同一思路：**官方页面 + 本地壳层保活**。

## 为什么需要它

ZCode 官方的远程入口是手机浏览器打开 `https://zcode.z.ai/remote/<id>`，链路为：

```
手机网页  ⇄  wss://zcode.z.ai/ws 中继  ⇄  桌面端 ZCode
```

手机浏览器在息屏、切后台时会把页面挂起（冻结定时器、断开 WebSocket），等回到前台时已经错过太多中继帧。桌面端日志里对应的痕迹是：

```
[web-remote-control] raw relay bridge degraded
  reasonCode: remote.rpcFrame.replayGraceExceeded
```

在我们机器上的统计：9/19 出现 16 次、9/21 16 次、10/3 13 次、10/4 7 次；手机离线期间出站消息最多一次被丢弃 101 条。**这是浏览器载体的天生缺陷，不是网络问题** —— 所以本应用用原生壳 + 前台服务来托底。

## 做法

- **内嵌官方页面**：WebView 加载 `zcode.z.ai/remote/…`，功能、UI、审批流程全部与官方一致，不做任何协议逆向。
- **前台保活服务**（`KeepAliveService`）：
  - 前台服务（`dataSync`）+ 常驻通知，进程不被回收；
  - `PARTIAL_WAKE_LOCK` + 低延迟 WiFi 锁，息屏后 CPU/无线不睡；
  - 监听网络回调，Wi‑Fi/流量切换恢复后自动重载页面；
  - 30 秒心跳探测 `navigator.onLine`，状态实时显示在通知与顶栏。
- **便捷配对**：支持点开系统里的远程链接（Deep Link）或“分享”链接到本应用；链接只存本机。
- **兜底**：WebView 渲染进程崩溃自动重建；页面内 JS 弹窗正常弹出；站外链接交给系统浏览器。

## 构建与安装

本仓库已配置 GitHub Actions（`.github/workflows/build-apk.yml`）：

1. 把仓库推到 GitHub（如 `EliotOK/zcode-light`）；
2. Actions 里 `Build APK` 产出的 artifact 即 `app-debug.apk`；
3. 打 tag（如 `v0.1.0`）时 APK 会自动挂到 Release。

本地构建要求 JDK 17、Android SDK 34（`gradlew assembleDebug`）。需要 Android 8.0+。

## 使用

1. 电脑端 ZCode 打开「远程控制」，生成配对；
2. 把手机网页链接（`https://zcode.z.ai/remote/…`）粘贴进 APP（或直接点开/分享该链接）；
3. 建议在配对页点一次「申请忽略电池优化」；之后 APP 会常驻保活、自动重连。

## 边界（与官方一致）

- 电脑关机、休眠或退出 ZCode 仍会断连——这是服务端不在的问题；
- 审批、配对等能力以官方页面为准，本应用不额外提权；
- Android 15 对 `dataSync` 前台服务有时长限制（本项目 targetSdk 34 暂不受影响），后续可迁移 `specialUse`。

## 隐私

配对链接仅保存在手机本地 SharedPreferences；应用不收集、不上传任何数据。
