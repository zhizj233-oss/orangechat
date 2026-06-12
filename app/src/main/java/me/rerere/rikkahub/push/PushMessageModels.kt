package me.rerere.rikkahub.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 推送相关的数据模型。
 *
 * 注意：这里的所有字段都做了空安全/默认值处理，因为推送正文来自外部（后端），
 * 不能假设结构一定完整。正式的完整消息内容后续由客户端根据 [messageId] 向后端拉取。
 */

/** 推送消息类型常量 */
object PushMessageType {
    /** 后端主动下发的 AI 消息 */
    const val PROACTIVE_MESSAGE = "proactive_message"
}

/**
 * 后端主动消息（data message）解析结果。
 *
 * 约定的 data 负载格式：
 * ```json
 * {
 *   "type": "proactive_message",
 *   "messageId": "xxx",
 *   "conversationId": "xxx",
 *   "title": "你的 AI 助手有新消息",
 *   "preview": "消息预览文本"
 * }
 * ```
 */
@Serializable
data class ProactivePushMessage(
    val type: String = "",
    val messageId: String? = null,
    val conversationId: String? = null,
    val title: String? = null,
    val preview: String? = null,
) {
    /** 校验关键字段是否齐全（messageId / conversationId 必须存在） */
    val isValid: Boolean
        get() = !messageId.isNullOrBlank() && !conversationId.isNullOrBlank()
}

/**
 * 上报给后端的设备推送 token 数据结构。
 *
 * 安全要求：客户端只保存并上报设备 push token，绝不包含任何 client secret /
 * 服务端密钥 / access token。
 */
@Serializable
data class PushTokenUpload(
    @SerialName("token")
    val token: String,
    @SerialName("provider")
    val provider: String = "huawei",
    @SerialName("device_model")
    val deviceModel: String,
    @SerialName("device_manufacturer")
    val deviceManufacturer: String,
    @SerialName("os_version")
    val osVersion: String,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("package_name")
    val packageName: String,
)
