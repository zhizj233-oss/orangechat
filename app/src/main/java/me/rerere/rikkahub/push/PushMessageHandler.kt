package me.rerere.rikkahub.push

import android.content.Context
import android.util.Log

/**
 * 推送消息的统一处理入口。
 *
 * 当前职责（前期接入阶段）：
 * 1. 校验 messageId / conversationId；
 * 2. 记录日志；
 * 3. 调用 [NotificationHelper] 显示本地通知；
 * 4. 为后续“点击通知进入指定会话”和“从后端拉取完整消息”预留接口。
 *
 * 故意不做的事（后续再接入，避免破坏现有业务）：
 * - 不修改 RikkaHub 的消息数据库结构；
 * - 不把主动消息插入聊天历史。
 */
class PushMessageHandler(
    private val context: Context,
    private val notificationHelper: NotificationHelper,
) {
    companion object {
        private const val TAG = "PushMessageHandler"
    }

    /**
     * 处理后端主动消息。
     *
     * @return 是否成功受理（字段非法时返回 false，不会崩溃）。
     */
    fun handleProactiveMessage(message: ProactivePushMessage): Boolean {
        if (!message.isValid) {
            Log.w(
                TAG,
                "Invalid proactive message, missing messageId/conversationId: " +
                    "messageId=${message.messageId}, conversationId=${message.conversationId}"
            )
            return false
        }

        Log.i(
            TAG,
            "Received proactive message: messageId=${message.messageId}, " +
                "conversationId=${message.conversationId}, title=${message.title}"
        )

        val shown = notificationHelper.showProactiveMessage(
            messageId = message.messageId,
            conversationId = message.conversationId,
            title = message.title,
            preview = message.preview,
        )

        // TODO(backend): 推送正文可能不完整，后续在用户点击通知后，
        //  根据 messageId 调用后端接口拉取完整消息并写入会话。
        //  预留接口见 onOpenConversation() / fetchFullMessage()。

        return shown
    }

    /**
     * 预留：点击通知进入指定会话时的回调入口。
     *
     * 当前跳转逻辑由 [NotificationHelper] 构建的 PendingIntent + RouteActivity 完成，
     * 此方法留给后续需要在跳转前/后做额外处理（如标记已读、预加载）时使用。
     */
    fun onOpenConversation(conversationId: String, messageId: String?) {
        // TODO(backend): 后续实现进入指定会话前的处理逻辑。
        Log.d(TAG, "onOpenConversation reserved: conversationId=$conversationId, messageId=$messageId")
    }

    /**
     * 预留：根据 messageId 从后端拉取完整消息。
     *
     * 推送正文仅作为预览，正式内容以后端为准。
     */
    fun fetchFullMessage(messageId: String) {
        // TODO(backend): 接入真实后端接口，根据 messageId 拉取完整消息内容。
        Log.d(TAG, "fetchFullMessage reserved: messageId=$messageId")
    }
}
