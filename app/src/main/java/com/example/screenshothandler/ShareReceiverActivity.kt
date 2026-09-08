package com.example.screenshothandler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ShareReceiverActivity : Activity() {

    private val webhookKey = "4320cf56-1718-49cf-9213-f990137da06a"
    private val sendUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=$webhookKey"
    private val uploadMediaUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/upload_media?key=$webhookKey&type=file"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUris = mutableListOf<Uri>()

        if (intent?.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) targetUris.add(uri)
        } else if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!uris.isNullOrEmpty()) {
                targetUris.addAll(uris)
            }
        }

        if (targetUris.isNotEmpty()) {
            Toast.makeText(this, "正在上传 ${targetUris.size} 个内容...", Toast.LENGTH_SHORT).show()
            uploadFiles(targetUris)
        } else {
            Toast.makeText(this, "未获取到有效文件", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun uploadFiles(uris: List<Uri>) {
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var failCount = 0

            for (uri in uris) {
                try {
                    val fileName = queryFileName(uri) ?: "unknown_file"
                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArrayOutputStream()
                        input.copyTo(buffer)
                        buffer.toByteArray()
                    } ?: throw Exception("无法读取数据")

                    // 1. 小于等于 2MB 的图片直接通过 Base64 通道发送
                    if (mimeType.startsWith("image/") && bytes.size <= 2 * 1024 * 1024) {
                        val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val md5Data = bytes.toMd5Hex()

                        val json = JSONObject().apply {
                            put("msgtype", "image")
                            put("image", JSONObject().apply {
                                put("base64", base64Data)
                                put("md5", md5Data)
                            })
                        }
                        if (sendJsonMessage(json)) successCount++ else failCount++
                    } 
                    // 2. 普通文件（或大于 2MB 且在 20MB 以内的图片/文件）走文件通道
                    else {
                        if (bytes.size > 20 * 1024 * 1024) {
                            failCount++
                            continue
                        }
                        // 第一步：上传临时素材
                        val mediaId = uploadMedia(bytes, fileName, mimeType)
                        if (mediaId != null) {
                            // 第二步：通过 media_id 发送文件消息
                            val json = JSONObject().apply {
                                put("msgtype", "file")
                                put("file", JSONObject().apply {
                                    put("media_id", mediaId)
                                })
                            }
                            if (sendJsonMessage(json)) successCount++ else failCount++
                        } else {
                            failCount++
                        }
                    }
                } catch (e: Exception) {
                    failCount++
                }
            }

            val resultMsg = if (failCount == 0) {
                "已成功发送 $successCount 个文件"
            } else {
                "成功 $successCount 个, 失败 $failCount 个 (可能超过20MB)"
            }
            showToastOnMain(resultMsg)
        }

        finish()
    }

    private fun uploadMedia(bytes: ByteArray, fileName: String, mimeType: String): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "media",
                fileName,
                bytes.toRequestBody(mimeType.toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(uploadMediaUrl)
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: "{}")
        return if (json.optInt("errcode") == 0) {
            json.optString("media_id")
        } else {
            null
        }
    }

    private fun sendJsonMessage(json: JSONObject): Boolean {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(sendUrl)
            .post(json.toString().toRequestBody(mediaType))
            .build()

        val response = okHttpClient.newCall(request).execute()
        val resJson = JSONObject(response.body?.string() ?: "{}")
        return resJson.optInt("errcode") == 0
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun ByteArray.toMd5Hex(): String {
        val digest = MessageDigest.getInstance("MD5").digest(this)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun showToastOnMain(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}