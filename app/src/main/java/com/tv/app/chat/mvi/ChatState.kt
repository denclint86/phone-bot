package com.tv.app.chat.mvi

import com.tv.app.chat.mvi.bean.ChatMessage
import java.util.UUID

/**
 * 不要直接操作 messages，请使用 modify list
 */
data class ChatState(
    val messages: List<ChatMessage> = listOf(),
    var listLastUpdatedTs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false
) {
    fun modifyMsg(uuid: UUID, block: ChatMessage.() -> ChatMessage): ChatState {
        val list = messages.toMutableList()
        val index = list.indexOfFirst { it.id == uuid }
        if (index == -1)
            return this
        else
            list[index] = (list[index].block())
        return newThis(list)
    }

    fun modifyList(block: MutableList<ChatMessage>.() -> Unit): ChatState {
        val list = messages.toMutableList()
        list.block()
        return newThis(list)
    }

    fun setLastPending(): ChatState {
        val list = messages.toMutableList()
        if (list.isNotEmpty()) {
            list[list.lastIndex] = list.last().copy(isPending = false)
            return newThis(list)
        } else {
            return this
        }
    }

    private fun newThis(list: MutableList<ChatMessage>) =
        copy(messages = list.toList(), listLastUpdatedTs = System.currentTimeMillis())
}