package com.tv.app.ui.suspend

import androidx.lifecycle.MutableLiveData

object SuspendLiveDataManager {
    // 控制普通Service悬浮窗的显示和隐藏
    val isShowSuspendWindow = MutableLiveData(true)

    // 悬浮窗显示的文本
    val suspendText = MutableLiveData("bot")
}