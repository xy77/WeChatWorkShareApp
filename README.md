# WeChatWorkShareApp & LanDrop

本仓库包含两个完全独立的 Android 工具应用（独立包名，可同时安装在同一台测试机上）：

1. **`:app`（发送到企微）**：在 Android 设备截屏分享时，自动将图片通过 Webhook 推送到企业微信群。
2. **`:landrop`（LanDrop）**：局域网文件与文本接收器，电脑（Mac/Windows）浏览器打开手机显示的局域网地址即可拖拽传文件到手机 `Download` 目录或发送文本。

## GitHub Actions 自动编译出 APK 说明
1. 将本项目覆盖推送到 GitHub 仓库（注意必须覆盖隐藏目录 `.github/workflows/build.yml`）。
2. 进入 GitHub 仓库的 **Actions** 页面等待工作流完成。
3. 在 **Artifacts** 中分别下载：
   - `WeChatWorkShare`（内含 `WeChatWorkShare.apk`）：原有「发送到企微」APK
   - `LanDrop`（内含 `LanDrop.apk`）：新增「LanDrop」局域网传文件 APK
