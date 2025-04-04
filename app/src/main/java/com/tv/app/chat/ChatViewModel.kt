package com.tv.app.chat

import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.type.FunctionResponsePart
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.tv.app.App
import com.tv.app.SLEEP_TIME
import com.tv.app.SYSTEM_PROMPT
import com.tv.app.addReturnChars
import com.tv.app.chat.mvi.ChatEffect
import com.tv.app.chat.mvi.ChatIntent
import com.tv.app.chat.mvi.ChatState
import com.tv.app.chat.mvi.bean.ChatMessage
import com.tv.app.chat.mvi.bean.funcMsg
import com.tv.app.chat.mvi.bean.modelMsg
import com.tv.app.chat.mvi.bean.systemMsg
import com.tv.app.chat.mvi.bean.userMsg
import com.tv.app.func.FuncManager
import com.tv.app.func.models.VisibleViewsModel
import com.tv.app.ui.suspend.ItemViewTouchListener
import com.tv.app.ui.suspend.SuspendLiveDataManager
import com.zephyr.extension.mvi.MVIViewModel
import com.zephyr.global_values.TAG
import com.zephyr.log.logE
import com.zephyr.net.toPrettyJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject


var windowListener: ItemViewTouchListener.OnTouchEventListener? = null

class ChatViewModel : MVIViewModel<ChatIntent, ChatState, ChatEffect>(),
    ItemViewTouchListener.OnTouchEventListener {
    companion object {
        private const val ERROR_UI_MSG = "出错了，请重试"
        private const val CHAT_TAG = "ChatTag" // 用于过滤日志
    }

    init {
        windowListener = this

        observeState {
            viewModelScope.launch {
                map { it.messages }.collect { list ->
                    val last = list.last()
                    SuspendLiveDataManager.suspendText.value = when {
                        last.role == Role.SYSTEM -> "未开始聊天"
                        last.role == Role.MODEL && last.text.isNotBlank() -> {
                            App.binder.get()?.setProgressBarVisibility(View.INVISIBLE)
                            last.text.take(4) + "..."
                        }

                        else -> {
                            App.binder.get()?.setProgressBarVisibility(View.VISIBLE)
                            "正在生成"
                        }
                    }
                }
            }
        }
    }

    override fun onClick() {
//        sendIntent(ChatIntent.Chat("[application-reminding]:call functions if needed"))
    }

    override fun onDrag() {
    }

    override fun onLongPress() {
    }

    override fun onCleared() {
        windowListener = null
        super.onCleared()
    }

    override fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.Chat -> chat(intent.text)
            ChatIntent.ResetChat ->
                viewModelScope.launch {
                    resetChat()
                }
        }
    }

    override fun initUiState(): ChatState = ChatState(
        listOf(
            systemMsg(SYSTEM_PROMPT)
        )
    )

    /**
     * 对话日志
     */
    private fun logC(string: String, cut: Boolean = true) =
        logE(
            CHAT_TAG,
            if (cut) string.addReturnChars(40) else string
        )

    private suspend fun resetChat() {
        ChatManager.resetChat()
    }

    private fun chat(text: String) = viewModelScope.launch(Dispatchers.IO) {
        if (ChatManager.isActive()) {
            sendEffect(ChatEffect.Generating)
            return@launch
        }
        sendEffect(ChatEffect.ChatSent(true))

        logC("user:\n$text")
        val userMessage = userMsg(text, false)
        updateState { modifyList { add(userMessage) } }

        val modelMsg = modelMsg("", true)
        updateState { modifyList { add(modelMsg) } }

        try {
            processResponse(ChatManager.sendMsg(userContent { text(text) }), modelMsg)
        } catch (t: Throwable) {
            t.logE(TAG)
            updateState {
                modifyMsg(modelMsg.id) {
                    copy(
                        text = (this.text + "\n" + ERROR_UI_MSG + "\n" + t.message).trim(),
                        isPending = false
                    )
                }
            }
        }
    }

    /**
     * 处理非流式对话响应
     */
    private suspend fun processResponse(
        response: GenerateContentResponse,
        modelMsg: ChatMessage
    ) {
        val responseText = response.text ?: ""
        val funcCalls = response.functionCalls.map { Pair(it.name, it.args) }

        updateState {
            modifyMsg(modelMsg.id) { copy(text = responseText, isPending = false) }
        }

        logC("llm:\n$responseText")
        if (funcCalls.isNotEmpty()) {
            logC("tools:\nabout to call ${funcCalls.size} functions")
            handleFunctionCalls(funcCalls)
        } else {
            logC("tools:\nno function was called")
        }
    }

    /**
     * 调用本地函数，统一将结果发送给大模型
     */
    private suspend fun handleFunctionCalls(funcCalls: List<Pair<String, Map<String, String?>>>) {
        val jsons = StringBuilder()
        val results = mutableMapOf<String, JSONObject>()
        withContext(Dispatchers.IO) {
            funcCalls.forEach { (name, args) ->
                val r = FuncManager.executeFunction(name, args)
                jsons.append("$name($args): ${r.toPrettyJson()}\n")
                results[name] = JSONObject(r)
            }
        }

        updateState {
            modifyList { add(funcMsg(jsons.toString(), false)) }
        }

        logC("tools:\nhandled ${results.size} functions")
        logC("tools:\n$jsons", false)

        logE(TAG, "sleep")
        delay(SLEEP_TIME)

        val responseMsg = modelMsg("", true)
        updateState {
            modifyList { add(responseMsg) }
        }

        sendEffect(ChatEffect.ChatSent(false))

        val funcResponse = try {
            ChatManager.sendMsg(
                funcContent {
                    funcCalls.forEach { pair ->
                        val name = pair.first
                        val bitmap =
                            if (name == VisibleViewsModel.name) getScreenAsBitmap() else null

                        results[name]?.let {
                            if (bitmap != null)
                                image(bitmap)
                            part(FunctionResponsePart(name, it))
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            t.logE(TAG)
            updateState {
                modifyMsg(responseMsg.id) {
                    copy(
                        text = (text + "\n" + ERROR_UI_MSG + "\n" + t.message).trim(),
                        isPending = false
                    )
                }
            }
            return
        }

        val funcResponseText = funcResponse.text ?: ""
        val newFuncCalls = funcResponse.functionCalls.map { Pair(it.name, it.args) }

        updateState {
            modifyMsg(responseMsg.id) { copy(text = funcResponseText, isPending = false) }
        }

        if (funcResponseText.isNotEmpty()) {
            logC("llm:\n$funcResponseText")
        }

        if (newFuncCalls.isNotEmpty()) {
            handleFunctionCalls(newFuncCalls)
        }
    }

    private fun getScreenAsBitmap(): Bitmap? = runBlocking {
        App.binder.get()?.captureScreen()
    }
}