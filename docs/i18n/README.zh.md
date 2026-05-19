<div align="center">

<img src="../../assets/logo.png" alt="Pocket Claude" width="160" height="160">

# Pocket Claude

**你的私人 Claude，运行在你的手机上 —— 使用你自己的 Claude Pro/Max 订阅，托管在你自己的硬件上。**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2012%2B%20%7C%20Web-blue)](#)
[![Server](https://img.shields.io/badge/server-Python%203.10%2B-yellow)](#)
[![Status](https://img.shields.io/badge/status-public%20beta-green)](#)

[English](../../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · [Português (BR)](README.pt-BR.md) · **中文** · [日本語](README.ja.md)

</div>

---

## 关于

**Pocket Claude** 是为 Anthropic [Claude](https://claude.ai) 打造的自托管聊天前端，围绕你已有的 **Claude Pro 或 Max 订阅**构建。一个小巧的 Python 服务运行在你自己的 Linux 主机上（迷你 PC、树莓派、旧笔记本、NAS），并调起本地安装的 `claude` CLI；原生 Android 应用和内置 Web UI 通过 [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) 或 Cloudflare 隧道，从任何地方与之通信。

**为什么要做这个？** Anthropic 官方移动应用很棒，但它们对 Anthropic API 的计费独立于你的 Pro/Max 套餐。如果你已经付费订阅了 Claude Pro，并希望从手机上使用*这份*订阅 —— 同时具备面向家人/朋友的多用户认证、你自己的对话存储、云端 TTS 朗读、图像生成，以及横跨聊天历史的全文检索 —— 那就自己托管 Pocket Claude。

**无需额外的 Anthropic API key。** 认证使用的是你本地安装的 Claude Code CLI 所使用的 OAuth 会话（`claude login`）。Pocket Claude 调起 CLI，剩下的事情由 CLI 来处理。

> **注意** —— 这是一个自托管的业余项目，并非 Anthropic 官方产品。你需要自备 Pro/Max 订阅。我们从不查看或代理你的对话。

## 亮点

- 💬 **原生 Android 客户端**（Kotlin + Jetpack Compose，非 Web 包壳），以及作为备选/桌面方案的内置 **Web UI**
- 👨‍👩‍👧 **多用户认证**，使用 scrypt 密码哈希 —— 全家/全团队共享一个 Pro/Max 账号，每个人都有各自的私密聊天和设置
- 📡 **SSE 流式传输**，具备恰当的背压控制、重试拦截器，以及 60 秒连接保活（在 Tailscale Funnel 上也很稳）
- 📎 **宽松的文件附件支持** —— 图片、PDF、代码、配置、JSON、CSV、任意文本格式。可内联，也可通过 Claude `Read` 工具引用
- 🔍 **全文检索**横跨整个聊天历史（SQLite FTS5），应用内可跳转到匹配位置
- 🔊 **三种 TTS 提供方**用于朗读 —— Microsoft Edge（免费，免配置）、Gemini Direct API（免费层），以及 Google Cloud TTS（Chirp 3 HD，每月 100 万字符免费）。通过 Media3 提供锁屏音频控件
- 🎨 通过 Gemini Nano Banana 实现**图像生成** —— 独立界面、画廊、分享、图生图编辑
- 🎭 **四种系统提示词模式** —— Standard（Anthropic 默认）、Permissive、Ultra-Liberal、Custom
- 🛠 **逐聊天的技能开关** —— WebSearch、WebFetch、Bash 执行
- 🔐 对话与设置的 **AES-256 加密备份**
- 🌐 **7 种语言** —— 英语、德语、西班牙语、法语、巴西葡萄牙语、简体中文、日语
- 🌗 全面屏 Material You 设计，支持浅色与深色主题

## 架构

```
                ┌───────────────────────────┐
                │  Pocket Claude (Android)  │
                │      or built-in web UI   │
                └─────────────┬─────────────┘
                              │ HTTPS  (persistent URL)
                              ▼
              ┌─────────────────────────────────┐
              │  Tailscale Funnel               │
              │  (or Cloudflare Named Tunnel)   │
              └─────────────┬───────────────────┘
                              │ outbound tunnel — no port forwarding
                              ▼
                ┌────────────────────────────┐
                │      Your Linux host       │
                │   ┌──────────────────────┐ │
                │   │  pocket-claude.svc   │ │ ── FastAPI + SQLite (FTS5)
                │   │   (systemd unit)     │ │
                │   │     │                │ │
                │   │     └─ spawns:       │ │
                │   │        claude-code   │ │ ── your Pro/Max OAuth
                │   └──────────────────────┘ │
                │   ┌──────────────────────┐ │
                │   │   tailscaled.svc     │ │
                │   └──────────────────────┘ │
                └────────────────────────────┘
```

两个组件，一个仓库：

| 路径        | 说明                                                                       |
|-------------|----------------------------------------------------------------------------|
| `server/`   | Python FastAPI 后端 + 内置 Web UI，负责调起 `claude` CLI。                |
| `app/`      | Android 客户端（Kotlin + Jetpack Compose，Material 3）。                  |

## 快速开始

### 1 —— 安装服务端（Linux 主机，约 5 分钟）

在一台全新的 Ubuntu / Debian / Fedora 主机上：

```bash
curl -fsSL https://raw.githubusercontent.com/joshtech90/PocketClaude/main/server/deploy/install-linux.sh | sudo bash
```

安装脚本会创建系统用户 `pocket-claude`、将代码放到 `/opt/pocket-claude/`、安装 Claude Code CLI、把 Python 依赖装到 venv，写入一个 systemd 单元，并在端口 `8787`（loopback）上启动。

### 2 —— 用你的 Pro/Max 账号登录 Claude

```bash
sudo -u pocket-claude -H claude login
```

OAuth 流程会在终端中运行。和 Claude Code 使用的是同一套登录。

### 3 —— 用一个持久 URL 暴露服务（Tailscale Funnel，免费）

```bash
sudo bash /opt/pocket-claude/deploy/setup-tailscale-funnel.sh
```

完成后会打印公网 URL —— 形如 `https://your-host.your-tailnet.ts.net`。或者，deploy 目录里也有一个 Cloudflare Named Tunnel 的安装脚本，方便你使用自定义域名。

### 4 —— 安装 Android 应用

可以直接从 [最新版本](https://github.com/joshtech90/PocketClaude/releases) 下载 APK，或者自己构建：

```bash
cd app && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

打开应用 → **添加配置** → 输入你的 URL 和初始管理员密码（首次启动服务时写入到 `/opt/pocket-claude/data/INITIAL_PASSWORD.txt`）。首次登录后请修改密码。

### 5 —— （可选）用 Web UI 代替应用

直接在任意浏览器中打开你的隧道 URL。Web UI 由同一个 FastAPI 进程提供服务。

完整安装说明，包括 Cloudflare 路径、主机间迁移、日常更新流程以及故障排查：**[`server/deploy/README.md`](../../server/deploy/README.md)**。

## 多用户

首次启动后会创建一个 `Admin` 用户，初始密码写入到 `/opt/pocket-claude/data/INITIAL_PASSWORD.txt`。在应用里通过 **用户** 界面（仅限管理员）添加更多用户 —— 每个人都有自己的对话、设置和 API 密钥，全部都跑在你这一份 Pro/Max 订阅之上。

## TTS 朗读

三种提供方，可按用户分别选择：

| 提供方              | 音质                  | 配置                         | 费用                                              |
|---------------------|-----------------------|------------------------------|---------------------------------------------------|
| **Microsoft Edge**  | 良好                  | 无                           | 免费（由 Microsoft 托管）                        |
| **Gemini Direct**   | 优秀                  | AI Studio API key            | 免费层（每个 key 每天 10 次请求）                |
| **Google Cloud TTS** | 优秀（Chirp 3 HD）   | 服务账号 JSON                | 每月 100 万字符免费，之后约 16 美元/百万字符     |

在应用中：**设置 → 朗读 → 提供方**，选你合适的。支持自动朗读、声音选择器、语速调节，以及面向 Gemini Direct 免费层的多 key 池。

## 图像生成（Gemini Nano Banana）

自备 AI Studio API key（休闲使用免费层足够）。独立界面、画廊、分享到其他应用、图生图编辑。如果没有配置 key，UI 中相关功能会被禁用。

## 技术栈

**服务端。** Python 3.10+、FastAPI、Uvicorn、用于 SSE 的 `sse-starlette`、aiosqlite、SQLite + FTS5、[`claude-agent-sdk`](https://pypi.org/project/claude-agent-sdk/)、用于密码哈希的 `scrypt`（标准库），用于 AES-256 备份的 `pyzipper`，以及 `edge-tts` + Google Cloud TTS SDK + 面向 Gemini Direct 的 REST。

**应用。** Kotlin 2.0.21、Android Gradle Plugin 8.7.3、compileSdk 35、minSdk 31、Jetpack Compose Material 3（BOM 2024.12.01）、OkHttp + okhttp-sse、DataStore Preferences、Coil 3、AndroidX Media3（ExoPlayer + MediaSession），以及用于 Markdown 的 `compose-richtext`。

**Web UI。** 原生 JS，无构建步骤，作为静态文件由同一个 FastAPI 进程提供。

## 路线图

- [ ] iOS 客户端（协议已经文档化 —— 欢迎贡献）
- [ ] Docker / Docker Compose 部署，作为系统安装脚本的一键替代方案
- [ ] 应用端的语音输入（基于 Whisper）
- [ ] 按对话设置工具预算

## 贡献

欢迎提交 Pull Request —— 尤其欢迎翻译（每个 `values-XX/strings.xml` 和 `docs/i18n/README.*.md` 各自就是一份合适大小的 PR 工作量）、iOS 客户端，以及 Docker 打包。

翻译 PR：每种语言一个 PR。字符串位于 `app/app/src/main/res/values-XX/strings.xml` 和 `server/pocket_claude/webui/i18n.js`。

代码贡献：
1. 先开一个 issue 讨论范围
2. 让 diff 保持精简、聚焦
3. 沿用现有的写法，而不是引入新的

## 许可证

MIT —— 参见 [LICENSE](../../LICENSE)。

## 致谢

- [Anthropic](https://www.anthropic.com/) 提供 Claude 和 Claude Code CLI
- [Tailscale](https://tailscale.com/) 让零配置的公网隧道对个人用户免费可用
- [`claude-agent-sdk`](https://github.com/anthropics/claude-agent-sdk-python) 提供干净的 CLI-spawning 接口
- Compose、FastAPI 与 SQLite 团队
