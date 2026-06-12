package me.rerere.rikkahub.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.sendNotification

/**
 * 主动消息推送的本地通知工具。
 *
 * 复用项目已有的 [NotificationUtil] / [sendNotification] 能力，
 * 仅额外负责创建 [PROACTIVE_MESSAGES_CHANNEL_ID] 渠道以及构建点击跳转。
 */
class NotificationHelper(
    private val context: Context,
) {
    companion object {
        private const val TAG = "PushNotificationHelper"

        /** 主动消息通知渠道 */
        const val PROACTIVE_MESSAGES_CHANNEL_ID = "proactive_messages"

        /** 通知 extra：会话 ID（用于后续跳转到指定会话） */
        const val EXTRA_CONVERSATION_ID = "conversationId"

        /** 通知 extra：消息 ID（用于后续向后端拉取完整消息） */
        const val EXTRA_MESSAGE_ID = "pushMessageId"

        private const val DEFAULT_TITLE = "你的 AI 助手有新消息"
        private const val DEFAULT_PREVIEW = "你有一条新的 AI 主动消息"

        /** 通知 ID 基础值，按 messageId/conversationId 派生，避免互相覆盖 */
        private const val NOTIFICATION_ID_BASE = 30000
    }

    /**
     * 创建主动消息通知渠道（幂等，可重复调用）。
     */
    fun ensureChannel() {
        runCatching {
            val channel = NotificationChannelCompat
                .Builder(
                    PROACTIVE_MESSAGES_CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH
                )
                .setName("AI 主动消息")
                .setDescription("接收 AI 助手主动推送的消息")
                .setVibrationEnabled(true)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }.onFailure {
            Log.e(TAG, "ensureChannel failed", it)
        }
    }

    /**
     * 显示一条主动消息通知。
     *
     * - 标题优先使用 [title]，缺失时使用默认标题；
     * - 内容优先使用 [preview]，缺失时使用默认文案；
     * - 点击通知打开 App 主界面，并携带 [messageId] / [conversationId] extra；
     * - 未授予通知权限（Android 13+）时不会崩溃，仅记录日志。
     *
     * @return 是否成功弹出通知（无权限或异常时返回 false）。
     */
    fun showProactiveMessage(
        messageId: String?,
        conversationId: String?,
        title: String?,
        preview: String?,
    ): Boolean {
        ensureChannel()

        if (!NotificationUtil.hasNotificationPermission(context)) {
            Log.w(TAG, "showProactiveMessage skipped: POST_NOTIFICATIONS not granted")
            return false
        }

        val notificationTitle = title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
        val notificationContent = preview?.takeIf { it.isNotBlank() } ?: DEFAULT_PREVIEW
        val notificationId = buildNotificationId(messageId, conversationId)

        return runCatching {
            val pendingIntent = buildContentIntent(messageId, conversationId, notificationId)
            context.sendNotification(
                channelId = PROACTIVE_MESSAGES_CHANNEL_ID,
                notificationId = notificationId,
            ) {
                this.title = notificationTitle
                this.content = notificationContent
                smallIcon = R.drawable.small_icon
                autoCancel = true
                useDefaults = true
                useBigTextStyle = true
                contentIntent = pendingIntent
            }
        }.onFailure {
            Log.e(TAG, "showProactiveMessage failed", it)
        }.getOrDefault(false)
    }

    /**
     * 构建点击通知后的跳转 Intent。
     *
     * 当前做成最小可用：打开 App 主界面（[RouteActivity]），并保留
     * conversationId / messageId extra。[RouteActivity] 已支持读取
     * conversationId 跳转到指定会话；messageId 预留给后续从后端拉取完整消息。
     */
    private fun buildContentIntent(
        messageId: String?,
        conversationId: String?,
        notificationId: Int,
    ): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            messageId?.let { putExtra(EXTRA_MESSAGE_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildNotificationId(messageId: String?, conversationId: String?): Int {
        val key = messageId ?: conversationId
        return if (key.isNullOrBlank()) {
            NOTIFICATION_ID_BASE
        } else {
            NOTIFICATION_ID_BASE + (key.hashCode() and 0xFFFF)
        }
    }
}
