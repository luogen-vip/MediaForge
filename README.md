# MediaForge · 媒体工坊

> 作者：**罗根** · 开源仓库：<https://github.com/luogen-vip/MediaForge>

一款基于 **FFmpeg 真实转码** 的安卓媒体工具箱。所有转换都是像素级/采样级重编码，
经过 FFprobe 容器校验——**不是改后缀、不是模拟**。

## 功能

### 1. 视频 / 音频转换
- **视频 → 音频**：从系统选择视频（大小无限制），转成任意音频格式
- **音频 → 音频**：含「同格式改参数」（如 WAV→WAV 只改比特率/采样率）
- **视频 → 视频**：MP4 / MOV / MKV / WEBM / AVI / TS，编码 H.264 / H.265 / VP9 / MPEG4
- 支持的音频格式（全部真实编码）：
  WAV · MP3 · M4A(AAC) · AAC · FLAC · OGG(Vorbis) · OPUS · WMA · AIFF · AMR · AC3 · MP2 · ALAC

### 2. 专业参数（每项都可「保持默认」= 跟随源）
- 音频比特率（CBR 滑块 + VBR 质量档）
- 采样率：8000Hz ~ 192000Hz
- 声道：单声道 / 立体声 / 跟随源
- 位深（PCM）：16bit / 24bit / 32bit / 32bit 浮点
- FLAC 压缩等级 0-9
- 视频参数：编码器、CRF、编码速度、分辨率、帧率、音轨码率

### 3. 场景预设（自动套标准参数）
| 预设 | 标准 |
| --- | --- |
| 发行歌曲·审核标准 | WAV 48kHz / 16bit / 立体声（主流发行平台推荐） |
| 发行歌曲·MP3 | MP3 320kbps / 44.1kHz / 立体声 |
| 高保真收藏 | FLAC 无损 44.1kHz / 立体声 |
| 日常保存 | M4A 256kbps |
| 社交发送 | M4A 128kbps |
| 播客/语音 | MP3 128kbps 单声道 |

点选预设自动套用标准参数；套用后手动改动若偏离该场景标准，APP 会给出提醒，
可选择「仍然转换」或「恢复标准参数」。

### 4. 任务中心
- 多任务并发，数量无上限
- **真实进度**（FFmpeg Statistics 时间统计 ÷ 总时长）
- 完成后 FFprobe **校验输出真实容器格式**（防假转换）
- 点击**播放**预览（音频/视频均可，无需先下载）
- **下载**：默认保存到系统「下载」文件夹的 `MediaForge` 子目录（文件管理器直接可见），
  也可在「关于」页为音频/图片/其他分别自选保存位置
- **重命名**：已下载文件在系统文件管理器中即时生效（改多少次都生效）
- **裁剪（v1.2.0 全新可视化）**：时间轴高亮条直观显示保留区间与播放头位置；
  拖动滑块选择起止点 → 点「**试听**」从起点播放到终点自动停止（听到的就是裁剪后保留的部分）；
  播放中拖动滑块，声音立刻跳到你拖到的位置（拖到哪听到哪）。
  转换前（视频转音频 / 音频转音频）与任务页（对已完成结果再裁剪）两处裁剪均已支持；
  也可用秒数 / mm:ss 直接输入，与滑块双向同步
- 每个任务明确显示「视频 → 音频」「音频 → 音频（同格式改参数）」等类型与
  `MP4 → MP3` 这样的格式对照

### 5. 图片格式转换
- 支持 JPG / PNG / WebP / BMP / TIFF 目标格式，像素级重新编码
- 分辨率自定义（可保持宽高比），**实时预览**画幅变化
- JPEG/WebP 质量可调

### 6. 图片去水印（完全离线）
- 新增「去水印」页：**框选去水印**（拉矩形框住水印）与 **涂抹去水印**（画笔涂抹，笔刷大小可调，支持撤销/清空）
- 内置 **LaMa 深度学习修复模型**（开源 Apache-2.0，构建时自动 int8 量化打包进 APK），
  根据周围画面内容智能补全被去除的区域，效果自然融入、不留突兀痕迹
- 设备内存不足或模型缺失时自动切换**内置多尺度智能填充算法**兜底，功能永不崩溃
- 只对水印所在局部区域推理，保留原始分辨率；输出无损 PNG
- 去水印任务与转换任务显示在同一任务列表，真实进度、可下载、可重命名

### 6. 任意文件真实格式识别
- 读取文件头「魔数」识别真实格式：APK / ZIP / JAR / DOCX / XLSX / PPTX / EPUB /
  PDF / PNG / JPEG / GIF / BMP / WebP / TIFF / ICO / HEIC / MP4 / MOV / MKV / WebM /
  AVI / WAV / MP3 / AAC / FLAC / OGG / OPUS / WMA / AIFF / AMR / MIDI / 7Z / RAR /
  GZIP / TAR / EXE / ELF / Java Class / 字体 / SQLite / TXT / JSON / XML / HTML /
  Markdown / CSV / SRT …… 以及 README.md 这类文本文档
- **不被后缀欺骗**：后缀写 jpg 实际是 APK 也能识别出来，明确告知「后缀被改过」
- **损坏检测**：PNG 缺 IEND、JPEG 缺 FFD9、ZIP 缺 EOCD、PDF 缺 %%EOF 等截断迹象
- 无签名二进制数据同样如实告知

## 真实性保障
1. 全部编码使用真实编码器（libmp3lame / aac / flac / pcm_s16le / libopus / libx264 …）
2. 输出文件用 FFprobe 读取真实容器格式，与目标格式不符直接判任务失败
3. 进度来自 FFmpeg Statistics 的真实时间统计，非模拟进度条
4. 转换失败显示 FFmpeg 日志尾部，方便排查

## 云端编译（GitHub Actions）
推送即自动编译并发布 Release：
1. Actions 使用 JDK 17 + Gradle 8.9 构建
2. 生成临时 keystore 签名 → `zipalign` → `apksigner` 校验
3. APK 上传到 Release `v1.0.0` 与 Artifacts

本地构建：
```bash
gradle assembleRelease
```

## 技术栈
- Kotlin + Jetpack Compose (Material 3)
- FFmpegKit（社区维护版 `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl`，与官方 `com.arthenica` API 完全兼容）
- 最低支持 Android 8.0 (API 26)

## 许可证
GPL-3.0（因包含 x264/x265 等 GPL 编码器）
