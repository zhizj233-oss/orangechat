# 2026-06-12

## 集成 Huawei Push Kit 客户端前期接入

- 在 `settings.gradle.kts` 的 `pluginManagement` 与 `dependencyResolutionManagement` 中新增华为 Maven 仓库 `https://developer.huawei.com/repo/`
- 在 `gradle/libs.versions.toml` 中新增 `huawei-agconnect`(1.9.5.301)、`huawei-push`(6.12.0.300) 版本，以及 `huawei-agconnect-core`、`huawei-push` 库与 `huawei-agconnect` 插件（id 为 `com.huawei.agconnect.agcp`，兼容 AGP 9）
- 在根 `build.gradle.kts` 注册 AppGallery Connect 插件（`apply false`），在 `app/build.gradle.kts` 应用该插件并添加 `agconnect-core`、`push` 依赖
- 在 `AndroidManifest.xml` 注册继承 `HmsMessageService` 的 `.push.HuaweiPushMessageService`（含 `com.huawei.push.action.MESSAGING_EVENT`）；`POST_NOTIFICATIONS` 权限已存在，appid 由 agcp 插件自动注入
- 新增独立推送包 `me.rerere.rikkahub.push`：
  - 新增 `PushMessageModels.kt`：定义 `ProactivePushMessage`、`PushTokenUpload` 数据结构与 `PushMessageType` 常量
  - 新增 `NotificationHelper.kt`：创建 `proactive_messages` 通知渠道，显示本地通知，点击跳转 `RouteActivity` 并携带 conversationId/messageId，无通知权限时不崩溃
  - 新增 `PushTokenManager.kt`：主动获取 Push Token、接收 onNewToken、封装设备信息、上报后端（占位地址 + 失败重试），token 做脱敏日志
  - 新增 `PushMessageHandler.kt`：校验字段、记录日志、调用通知，并预留进入会话与拉取完整消息接口
  - 新增 `HuaweiPushMessageService.kt`：实现 onNewToken 与 onMessageReceived，宽松解析 data message，type 为 `proactive_message` 时转交处理器
- 在 `di/AppModule.kt` 注册 `NotificationHelper`、`PushMessageHandler`、`PushTokenManager`
- 在 `RikkaHubApp.onCreate` 新增 `initHuaweiPush()`：启动时创建推送渠道、尝试获取 token 并重试上报（不强制弹权限）
