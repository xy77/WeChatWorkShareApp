package com.local.landrop

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class FileServer(
    private val context: Context,
    port: Int = 8080,
    private val onLog: (String) -> Unit,
    private val onTextReceived: (String) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> {
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", webHtml)
            }
            session.method == Method.POST && session.uri == "/upload" -> handleFileUpload(session)
            session.method == Method.POST && session.uri == "/text" -> handleTextUpload(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun handleFileUpload(session: IHTTPSession): Response {
        val rawName = session.parameters["name"]?.firstOrNull()
            ?: "file_${System.currentTimeMillis()}"
        val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L

        if (contentLength <= 0L) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty file")
        }

        return try {
            saveStreamToDownloads(safeName, contentLength, session.inputStream)
            onLog("已接收文件: $safeName (${formatSize(contentLength)})")
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            onLog("接收失败: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleTextUpload(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty text")
        }
        return try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var remaining = contentLength
            while (remaining > 0) {
                val read = session.inputStream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            val text = out.toString("UTF-8")
            onTextReceived(text)
            onLog("已接收文本 (${text.length} 字)")
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun saveStreamToDownloads(fileName: String, contentLength: Long, input: InputStream) {
        val outputStream: OutputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法打开输出流")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, fileName))
        }

        outputStream.use { out ->
            val buffer = ByteArray(16 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.flush()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> String.format("%.2fMB", bytes / (1024.0 * 1024.0))
    }

    private val webHtml = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>LanDrop - 发送到安卓测试机</title>
          <style>
            * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
            body { max-width: 560px; margin: 40px auto; padding: 0 20px; background: #f5f7fa; color: #1f2937; }
            .card { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 10px rgba(0,0,0,0.06); margin-bottom: 20px; }
            h2 { margin: 0 0 16px; font-size: 18px; }
            .drop-zone { border: 2px dashed #cbd5e1; border-radius: 10px; padding: 36px 20px; text-align: center; cursor: pointer; transition: .2s; background: #f8fafc; }
            .drop-zone.dragover { border-color: #2563eb; background: #eff6ff; }
            .btn { background: #2563eb; color: #fff; border: none; border-radius: 8px; padding: 10px 18px; font-size: 14px; cursor: pointer; }
            .btn:hover { background: #1d4ed8; }
            textarea { width: 100%; height: 90px; border: 1px solid #d1d5db; border-radius: 8px; padding: 10px; font-size: 14px; resize: vertical; margin-bottom: 10px; }
            .item { margin-top: 12px; font-size: 13px; }
            progress { width: 100%; height: 8px; margin-top: 4px; }
            .status { color: #059669; font-weight: 500; }
            .err { color: #dc2626; }
          </style>
        </head>
        <body>
          <div class="card">
            <h2>发送文件到手机 Download 目录</h2>
            <div class="drop-zone" id="dropZone">
              点击选择文件，或将文件拖拽到此处（支持多选）
              <input type="file" id="fileInput" multiple style="display:none" />
            </div>
            <div id="fileList"></div>
          </div>

          <div class="card">
            <h2>发送文本 / 链接</h2>
            <textarea id="textInput" placeholder="输入测试链接、账号或文本..."></textarea>
            <button class="btn" onclick="sendText()">发送到手机</button>
            <span id="textStatus" style="margin-left:10px;font-size:13px;"></span>
          </div>

          <script>
            const dropZone = document.getElementById('dropZone');
            const fileInput = document.getElementById('fileInput');
            const fileList = document.getElementById('fileList');

            dropZone.onclick = () => fileInput.click();
            dropZone.ondragover = (e) => { e.preventDefault(); dropZone.classList.add('dragover'); };
            dropZone.ondragleave = () => dropZone.classList.remove('dragover');
            dropZone.ondrop = (e) => {
              e.preventDefault();
              dropZone.classList.remove('dragover');
              handleFiles(e.dataTransfer.files);
            };
            fileInput.onchange = () => handleFiles(fileInput.files);

            function handleFiles(files) {
              Array.from(files).forEach(uploadFile);
            }

            function uploadFile(file) {
              const row = document.createElement('div');
              row.className = 'item';
              row.innerHTML = '<div>' + file.name + ' <span class="st">上传中 0%</span></div><progress max="100" value="0"></progress>';
              fileList.prepend(row);

              const xhr = new XMLHttpRequest();
              xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name), true);
              xhr.setRequestHeader('Content-Type', 'application/octet-stream');

              xhr.upload.onprogress = (e) => {
                if (e.lengthComputable) {
                  const pct = Math.round((e.loaded / e.total) * 100);
                  row.querySelector('progress').value = pct;
                  row.querySelector('.st').textContent = '上传中 ' + pct + '%';
                }
              };
              xhr.onload = () => {
                const st = row.querySelector('.st');
                if (xhr.status === 200) {
                  st.textContent = '已完成';
                  st.className = 'st status';
                } else {
                  st.textContent = '失败: ' + xhr.responseText;
                  st.className = 'st err';
                }
              };
              xhr.onerror = () => {
                const st = row.querySelector('.st');
                st.textContent = '网络中断';
                st.className = 'st err';
              };
              xhr.send(file);
            }

            function sendText() {
              const text = document.getElementById('textInput').value.trim();
              const status = document.getElementById('textStatus');
              if (!text) return;
              fetch('/text', { method: 'POST', body: text })
                .then(r => {
                  status.textContent = r.ok ? '已发送' : '发送失败';
                  status.style.color = r.ok ? '#059669' : '#dc2626';
                  if (r.ok) document.getElementById('textInput').value = '';
                })
                .catch(() => { status.textContent = '连接失败'; status.style.color = '#dc2626'; });
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
