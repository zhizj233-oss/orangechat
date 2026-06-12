package me.rerere.rikkahub.push

import android.os.Bundle
import android.util.Log
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Huawei Push Kit 消息服务。
 *
 * - [onNewToken]：接收 push token，转交 [PushTokenManager]；
 * - [onMessageReceived]：解析后端 data message，type == proactive_message 时
 *   交由统一的 [PushMessageHandler] 处理。
 *
 * 注意：该 Service 由系统实例化，因此通过 Koin 的 [KoinComponent] 获取依赖，
 * 而非构造注入。所有外部输入均做容错处理，不阻塞主线程的耗时操作交给下游协程。
 */
class HuaweiPushMessageService : HmsMessageService(), KoinComponent {

    private val tokenManager: PushTokenManager by inject()
    private val messageHandler: PushMessageHandler by inject()

    // 对外部 JSON 输入保持宽松解析，避免后端字段变动导致解析崩溃
    private val tolerantJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    companion object {
        private const val TAG = "HuaweiPushService"
    }

    override fun onNewToken(token: String?, bundle: Bundle?) {
        super.onNewToken(token, bundle)
        Log.i(TAG, "onNewToken received")
        if (!token.isNullOrBlank()) {
            runCatching { tokenManager.onTokenReceived(token) }
                .onFailure { Log.e(TAG, "handle onNewToken failed", it) }
        } else {
            Log.w(TAG, "onNewToken: empty token")
        }
    }

    override fun onTokenError(e: Exception?) {
        super.onTokenError(e)
        Log.e(TAG, "onTokenError", e)
    }

    override fun onMessageReceived(message: RemoteMessage?) {
        super.onMessageReceived(message)
        if (message == null) {
            Log.w(TAG, "onMessageReceived: null message")
            return
        }

        val parsed = runCatching { parseMessage(message) }
            .onFailure { Log.e(TAG, "parse data message failed", it) }
            .getOrNull()

        if (parsed == null) {
            Log.w(TAG, "onMessageReceived: unable to parse data message")
            return
        }

        when (parsed.type) {
            PushMessageType.PROACTIVE_MESSAGE -> {
                runCatching { messageHandler.handleProactiveMessage(parsed) }
                    .onFailure { Log.e(TAG, "handleProactiveMessage failed", it) }
            }

            else -> {
                Log.d(TAG, "Ignored push message of type=${parsed.type}")
            }
        }
    }

    /**
     * 解析 data message。优先解析 [RemoteMessage.getData] 的 JSON 字符串，
     * 失败时回退到 key/value 形式的 [RemoteMessage.getDataOfMap]。
     */
    private fun parseMessage(message: RemoteMessage): ProactivePushMessage? {
        val data = message.data
        if (!data.isNullOrBlank()) {
            val fromJson = runCatching {
                tolerantJson.decodeFromString(ProactivePushMessage.serializer(), data)
            }.getOrNull()
            if (fromJson != null) return fromJson
        }

        // 回退：key/value 形式
        val map = runCatching { message.dataOfMap }.getOrNull()
        if (!map.isNullOrEmpty()) {
            return ProactivePushMessage(
                type = map["type"] ?: "",
                messageId = map["messageId"],
                conversationId = map["conversationId"],
                title = map["title"],
                preview = map["preview"],
            )
        }
        return null
    }
}
