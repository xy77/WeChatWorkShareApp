package com.example.screenshothandler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ShareReceiverActivity : Activity() {

    private val webhookUrl =
        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=4320cf56-1718-49cf-9213-f990137da06a"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
                Toast.makeText(this, "正在上传至企业微信...", Toast.LENGTH_SHORT).show()
                uploadImage(imageUri)
            } else {
                Toast.makeText(this, "获取截图失败：URI 为空", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun uploadImage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    input.copyTo(buffer)
                    buffer.toByteArray()
                } ?: throw Exception("无法读取截图数据")

                if (bytes.size > 2 * 1024 * 1024) {
                    showToastOnMain("上传失败：截图超过企微 2MB 限制")
                    return@launch
                }

                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val md5Data = bytes.toMd5Hex()

                val jsonBody = JSONObject().apply {
                    put("msgtype", "image")
                    put("image", JSONObject().apply {
                        put("base64", base64Data)
                        put("md5", md5Data)
                    })
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonBody.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(requestBody)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                val resJson = JSONObject(responseString)
                if (resJson.optInt("errcode") == 0) {
                    showToastOnMain("截图已发送到企微群")
                } else {
                    showToastOnMain("企微接口返回错误: ${resJson.optString("errmsg")}")
                }
            } catch (e: Exception) {
                showToastOnMain("发送失败: ${e.message}")
            }
        }

        finish()
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
