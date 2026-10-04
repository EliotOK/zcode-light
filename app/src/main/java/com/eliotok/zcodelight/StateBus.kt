package com.eliotok.zcodelight

import kotlinx.coroutines.flow.MutableStateFlow

/** 服务与界面之间的轻量状态总线。 */
object StateBus {
    /** 当前生效的官方远程链接；空表示尚未配对。 */
    val remoteUrl = MutableStateFlow("")

    /** 展示在顶栏与通知里的连接状态。 */
    val status = MutableStateFlow("未连接")

    /** WebView 渲染进程崩溃后请求重建界面的计数。 */
    val recreateTick = MutableStateFlow(0)
}
