package com.tv.app

import androidx.datastore.preferences.core.intPreferencesKey
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zephyr.datastore.getPreference
import com.zephyr.datastore.putPreference
import com.zephyr.global_values.TAG
import com.zephyr.global_values.globalContext
import com.zephyr.log.logE
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

object ApiModelProvider {
    private val indexPref = intPreferencesKey("pref_index")

    private val apiKeys: List<String> by lazy { loadApiKeys().toList() }
    private var currentIndex: AtomicInteger
    private val switchTimestamps = ConcurrentLinkedQueue<Long>()

    init {
        runBlocking {
            currentIndex = AtomicInteger(getPreference(indexPref, 0))
        }
    }

    /**
     * 请求一次换一个，以避免被谷歌限速
     * 使用 AtomicInteger 无锁操作，适合低并发场景
     */
    private suspend fun getNextKey(): String {
        if (apiKeys.isEmpty()) {
            throw IllegalStateException("没有设置 key")
        }
        val index = currentIndex.getAndUpdate { current ->
            (current + 1) % apiKeys.size
        }
        return apiKeys[index].also {
            val currentTime = System.currentTimeMillis()
            switchTimestamps.add(currentTime)
            putPreference(indexPref, index)
            logE(TAG, "api-key switched to index: $index (${it.takeLast(5)})")
            logE(TAG, "api-key request rate: ${getSwitchRatePerMinute()}")
        }
    }

    suspend fun createModel() =
        GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = getNextKey(),
            systemInstruction = content { text(SYSTEM_PROMPT) },
            tools = APP_TOOLS,
            generationConfig = GEMINI_CONFIG
        )

    fun getCount(): Int = apiKeys.size

    private fun loadApiKeys(): Set<String> {
        val inputStream: InputStream = globalContext?.resources?.openRawResource(R.raw.keys)
            ?: return setOf()
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        return Gson().fromJson(jsonString, object : TypeToken<Set<String>>() {}.type)
    }

    fun getSwitchRatePerMinute(): Int {
        val currentTime = System.currentTimeMillis()
        val oneMinuteAgo = currentTime - 60_000 // 60秒 = 1分钟

        // 移除超过一分钟的时间戳
        while (switchTimestamps.isNotEmpty() && switchTimestamps.peek()!! < oneMinuteAgo) {
            switchTimestamps.poll()
        }

        // 计算当前队列中的时间戳数量，即过去一分钟的切换次数
        return switchTimestamps.size
    }
}