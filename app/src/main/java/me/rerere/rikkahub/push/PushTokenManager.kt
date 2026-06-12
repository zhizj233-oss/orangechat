package me.rerere.rikkahub.push

import android.content.Context
import android.os.Build
import android.util.Log
import com.huawei.agconnect.AGConnectOptionsBuilder
import com.huawei.hms.aaid.HmsInstanceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Huawei Push Token 管理器。
 *
 * 职责：
 * - 主动获取 Huawei Push Token（[fetchToken]）；
 * - 接收 [HuaweiPushMessageService.onNewToken] 回调的 token（[onTokenReceived]）；
 * - 将 token + provider="huawei" + 设备信息封装为 [PushTokenUpload] 上报后端；
 * - token 上传失败不崩溃，记录日志并允许后续重试（[retryUploadIfNeeded]）。
 *
 * 安全：客户端只持有/上报设备 push token，不涉及任何服务端密钥。
 */
class PushTokenManager(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "PushTokenManager"

        /** HMS Push token scope，固定为 "HCM" */
        private const val HCM_SCOPE = "HCM"

        private const val PREFS_NAME = "huawei_push_prefs"
        private const val KEY_TOKEN = "push_token"
        private const val KEY_UPLOADED = "push_token_uploaded"

        /**
         * TODO(backend): 替换为真实后端上报地址（建议放入 SettingsStore / 远程配置，
         *  不要硬编码到代码里）。留空时仅记录日志、不发起网络请求。
         */
        private const val BACKEND_UPLOAD_URL = ""
    }

    @Volatile
    private var cachedToken: String? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 主动获取 Huawei Push Token（异步，不阻塞主线程）。
     *
     * 说明：HMS Push SDK 通常会在应用启动时自动下发 token 到
     * [HuaweiPushMessageService.onNewToken]，此方法用于主动触发一次获取。
     */
    fun fetchToken() {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val appId = AGConnectOptionsBuilder()
                    .build(context)
                    .getString("client/app_id")
                if (appId.isNullOrBlank()) {
                    Log.w(TAG, "fetchToken: appId not found in agconnect config")
                    return@launch
                }
                // getToken 为阻塞调用，已在 IO 线程执行
                val token = HmsInstanceId.getInstance(context).getToken(appId, HCM_SCOPE)
                if (token.isNullOrBlank()) {
                    Log.i(TAG, "fetchToken: token not ready yet, will be delivered via onNewToken")
                } else {
                    Log.i(TAG, "fetchToken success: ${maskToken(token)}")
                    onTokenReceived(token)
                }
            }.onFailure {
                Log.e(TAG, "fetchToken failed", it)
            }
        }
    }

    /**
     * 接收新 token（来自 onNewToken 或主动获取），持久化并尝试上报。
     */
    fun onTokenReceived(token: String) {
        if (token.isBlank()) {
            Log.w(TAG, "onTokenReceived: blank token ignored")
            return
        }
        val changed = token != cachedToken || prefs.getString(KEY_TOKEN, null) != token
        cachedToken = token
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .apply()
        if (changed) {
            // token 变化，标记未上传
            prefs.edit().putBoolean(KEY_UPLOADED, false).apply()
        }
        Log.i(TAG, "onTokenReceived: ${maskToken(token)} (changed=$changed)")
        uploadToken(token)
    }

    /** 返回当前缓存/持久化的 token（可能为 null） */
    fun getCurrentToken(): String? = cachedToken ?: prefs.getString(KEY_TOKEN, null)

    /**
     * 启动时调用：若已有 token 但尚未成功上报，则重试一次。
     */
    fun retryUploadIfNeeded() {
        val token = getCurrentToken() ?: return
        if (!prefs.getBoolean(KEY_UPLOADED, false)) {
            Log.i(TAG, "retryUploadIfNeeded: retrying upload for ${maskToken(token)}")
            uploadToken(token)
        }
    }

    /**
     * 将 token 封装为 [PushTokenUpload] 并上报后端（异步，失败不崩溃）。
     */
    fun uploadToken(token: String) {
        val payload = buildUploadPayload(token)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val bodyJson = json.encodeToString(PushTokenUpload.serializer(), payload)

                if (BACKEND_UPLOAD_URL.isBlank()) {
                    // 后端地址尚未配置：仅记录将要上报的数据，便于后续接入。
                    Log.i(TAG, "uploadToken (no backend configured), payload=$bodyJson")
                    return@launch
                }

                val request = Request.Builder()
                    .url(BACKEND_UPLOAD_URL)
                    .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        prefs.edit().putBoolean(KEY_UPLOADED, true).apply()
                        Log.i(TAG, "uploadToken success: code=${response.code}")
                    } else {
                        // 标记未上传，留待后续重试
                        prefs.edit().putBoolean(KEY_UPLOADED, false).apply()
                        Log.w(TAG, "uploadToken failed: code=${response.code}")
                    }
                }
            }.onFailure {
                prefs.edit().putBoolean(KEY_UPLOADED, false).apply()
                Log.e(TAG, "uploadToken error (will retry later)", it)
            }
        }
    }

    private fun buildUploadPayload(token: String): PushTokenUpload {
        return PushTokenUpload(
            token = token,
            provider = "huawei",
            deviceModel = Build.MODEL ?: "unknown",
            deviceManufacturer = Build.MANUFACTURER ?: "unknown",
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = BuildConfig.VERSION_NAME,
            packageName = context.packageName,
        )
    }

    private fun maskToken(token: String): String {
        if (token.length <= 8) return "****"
        return token.take(4) + "****" + token.takeLast(4)
    }
}
