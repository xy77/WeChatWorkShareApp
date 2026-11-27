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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileServer(
    private val context: Context,
    port: Int = 8080,
    private val onLog: (time: String, msg: String) -> Unit,
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

    private fun currentTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

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
            val time = currentTime()
            onLog(time, "已接收文件: $safeName (${formatSize(contentLength)})")
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, time)
        } catch (e: Exception) {
            onLog(currentTime(), "接收失败: ${e.message}")
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
            val time = currentTime()
            onTextReceived(text)
            onLog(time, "已接收文本 (${text.length} 字)")
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, time)
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
            body { max-width: 560px; margin: 40px auto; padding: 0 20px; background: #f5f7fa; color: #1f2937; min-height: calc(100vh - 80px); }
            .card { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 2px 10px rgba(0,0,0,0.06); margin-bottom: 20px; }
            h2 { margin: 0 0 16px; font-size: 18px; }
            .drop-zone { border: 2px dashed #cbd5e1; border-radius: 10px; padding: 36px 20px; text-align: center; cursor: pointer; transition: .2s; background: #f8fafc; }
            .drop-zone:hover { border-color: #2563eb; background: #eff6ff; }
            .btn { background: #2563eb; color: #fff; border: none; border-radius: 8px; padding: 10px 18px; font-size: 14px; cursor: pointer; }
            .btn:hover { background: #1d4ed8; }
            textarea { width: 100%; height: 90px; border: 1px solid #d1d5db; border-radius: 8px; padding: 10px; font-size: 14px; resize: vertical; margin-bottom: 10px; }
            .item { margin-top: 12px; font-size: 13px; }
            progress { width: 100%; height: 8px; margin-top: 4px; }
            .status { color: #059669; font-weight: 500; }
            .err { color: #dc2626; }
            .drag-overlay {
              position: fixed;
              inset: 0;
              background: rgba(37, 99, 235, 0.14);
              backdrop-filter: blur(2px);
              border: 3px dashed #2563eb;
              display: none;
              align-items: center;
              justify-content: center;
              z-index: 9999;
              pointer-events: none;
            }
            .drag-overlay.active { display: flex; }
            .drag-overlay-box {
              background: #ffffff;
              color: #1d4ed8;
              padding: 20px 32px;
              border-radius: 12px;
              font-size: 16px;
              font-weight: 600;
              box-shadow: 0 8px 24px rgba(37, 99, 235, 0.18);
            }
          </style>
        </head>
        <body>
          <div class="drag-overlay" id="dragOverlay">
            <div class="drag-overlay-box">松开鼠标，立即发送文件到手机</div>
          </div>

          <div class="card">
            <h2>发送文件到手机 Download 目录</h2>
            <div class="drop-zone" id="dropZone">
              点击选择文件，或拖拽文件到网页任意位置（支持多选）
              <input type="file" id="fileInput" multiple style="display:none" />
            </div>
            <div id="fileList"></div>
          </div>

          <div class="card">
            <h2>发送文本 / 链接</h2>
            <textarea id="textInput" placeholder="输入任意内容后发送"></textarea>
            <button class="btn" id="sendTextBtn" onclick="sendText()" style="display:none">发送到手机</button>
            <span id="textStatus" style="margin-left:10px;font-size:13px;"></span>
          </div>

          <script>
            const dropZone = document.getElementById('dropZone');
            const fileInput = document.getElementById('fileInput');
            const fileList = document.getElementById('fileList');
            const dragOverlay = document.getElementById('dragOverlay');
            const textInput = document.getElementById('textInput');
            const sendTextBtn = document.getElementById('sendTextBtn');

            function updateSendBtnVisibility() {
              sendTextBtn.style.display = textInput.value.trim().length > 0 ? 'inline-block' : 'none';
            }
            textInput.addEventListener('input', updateSendBtnVisibility);

            dropZone.onclick = () => fileInput.click();
            fileInput.onchange = () => {
              handleFiles(fileInput.files);
              fileInput.value = '';
            };

            let dragDepth = 0;
            window.addEventListener('dragenter', (e) => {
              e.preventDefault();
              dragDepth++;
              dragOverlay.classList.add('active');
            });
            window.addEventListener('dragover', (e) => {
              e.preventDefault();
            });
            window.addEventListener('dragleave', (e) => {
              e.preventDefault();
              dragDepth = Math.max(0, dragDepth - 1);
              if (dragDepth === 0) {
                dragOverlay.classList.remove('active');
              }
            });
            window.addEventListener('drop', (e) => {
              e.preventDefault();
              dragDepth = 0;
              dragOverlay.classList.remove('active');
              if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                handleFiles(e.dataTransfer.files);
              }
            });

            function handleFiles(files) {
              Array.from(files).forEach(uploadFile);
            }

            function uploadFile(file) {
              const row = document.createElement('div');
              row.className = 'item';
              const header = document.createElement('div');
              const nameSpan = document.createElement('span');
              nameSpan.textContent = file.name + ' ';
              const stSpan = document.createElement('span');
              stSpan.className = 'st';
              stSpan.textContent = '上传中 0%';
              header.appendChild(nameSpan);
              header.appendChild(stSpan);
              const prog = document.createElement('progress');
              prog.max = 100;
              prog.value = 0;
              row.appendChild(header);
              row.appendChild(prog);
              fileList.prepend(row);

              const xhr = new XMLHttpRequest();
              xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name), true);
              xhr.setRequestHeader('Content-Type', 'application/octet-stream');

              xhr.upload.onprogress = (e) => {
                if (e.lengthComputable) {
                  const pct = Math.round((e.loaded / e.total) * 100);
                  prog.value = pct;
                  stSpan.textContent = '上传中 ' + pct + '%';
                }
              };
              xhr.onload = () => {
                if (xhr.status === 200) {
                  const time = (xhr.responseText || '').trim();
                  stSpan.textContent = time ? ('已发送 [' + time + ']') : '已发送';
                  stSpan.className = 'st status';
                } else {
                  stSpan.textContent = '失败: ' + xhr.responseText;
                  stSpan.className = 'st err';
                }
              };
              xhr.onerror = () => {
                stSpan.textContent = '网络中断';
                stSpan.className = 'st err';
              };
              xhr.send(file);
            }

            function sendText() {
              const text = textInput.value.trim();
              const status = document.getElementById('textStatus');
              if (!text) return;
              fetch('/text', { method: 'POST', body: text })
                .then(async r => {
                  if (r.ok) {
                    const time = (await r.text()).trim();
                    status.textContent = time ? ('已发送 [' + time + ']') : '已发送';
                    status.style.color = '#059669';
                    textInput.value = '';
                    updateSendBtnVisibility();
                  } else {
                    status.textContent = '发送失败';
                    status.style.color = '#dc2626';
                  }
                })
                .catch(() => { status.textContent = '连接失败'; status.style.color = '#dc2626'; });
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
