<div align="center">

<img src="../../assets/logo.png" alt="Pocket Claude" width="160" height="160">

# Pocket Claude

**Seu Claude pessoal no seu celular — rodando na sua própria assinatura Claude Pro/Max, hospedado no seu próprio hardware.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2012%2B%20%7C%20Web-blue)](#)
[![Server](https://img.shields.io/badge/server-Python%203.10%2B-yellow)](#)
[![Status](https://img.shields.io/badge/status-public%20beta-green)](#)

[English](../../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · **Português (BR)** · [中文](README.zh.md) · [日本語](README.ja.md)

</div>

---

## Sobre

O **Pocket Claude** é um front-end de chat auto-hospedado para o [Claude](https://claude.ai) da Anthropic, construído em torno da sua assinatura existente do **Claude Pro ou Max**. Um pequeno servidor Python roda na sua própria máquina Linux (Mini-PC, Raspberry Pi, notebook antigo, NAS) e inicia o CLI `claude` instalado localmente; um app Android nativo e uma interface web embutida se comunicam com ele de qualquer lugar via [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) ou um túnel Cloudflare.

**Por que isso existe.** Os apps móveis oficiais da Anthropic são ótimos, mas eles cobram a API Anthropic separadamente do seu plano Pro/Max. Se você já está pagando pelo Claude Pro e quer usar *essa* assinatura no seu celular — com autenticação multiusuário para família/amigos, seu próprio armazenamento de conversas, leitura em voz alta via TTS na nuvem, geração de imagens e busca de texto completo em todo o seu histórico de chat — você hospeda o Pocket Claude você mesmo.

**Nenhuma chave de API Anthropic adicional é necessária.** A autenticação é a sessão OAuth que o seu CLI do Claude Code instalado localmente utiliza (`claude login`). O Pocket Claude inicia o CLI; o CLI cuida do resto.

> **Observação** — este é um projeto hobby auto-hospedado, não um produto da Anthropic. Você traz sua própria assinatura Pro/Max. Nunca vemos nem fazemos proxy das suas conversas.

## Destaques

- 💬 **Cliente Android nativo** (Kotlin + Jetpack Compose, sem web wrapper) e uma **interface web** embutida como fallback / opção para desktop
- 👨‍👩‍👧 **Autenticação multiusuário** com hashing de senha scrypt — toda sua família/equipe compartilha uma conta Pro/Max, cada um com chats e configurações privados
- 📡 **Streaming SSE** com backpressure adequado, interceptor de retry e keep-alive de conexão de 60 segundos (resiliente sobre Tailscale Funnel)
- 📎 **Anexos de arquivo liberais** — imagens, PDFs, código, configurações, JSON, CSV, qualquer formato de texto. Inseridos inline ou referenciados via a ferramenta `Read` do Claude
- 🔍 **Busca de texto completo** em todo o seu histórico de chat (SQLite FTS5), com salto para a correspondência no app
- 🔊 **Três provedores de TTS** para leitura em voz alta — Microsoft Edge (gratuito, sem configuração), Gemini Direct API (camada gratuita) e Google Cloud TTS (Chirp 3 HD, 1M caracteres/mês grátis). Controles de áudio na tela de bloqueio via Media3
- 🎨 **Geração de imagens** via Gemini Nano Banana — tela separada, galeria, compartilhamento, edição imagem para imagem
- 🎭 **Quatro modos de system prompt** — Padrão (default da Anthropic), Permissivo, Ultra-Liberal e Personalizado
- 🛠 **Toggles de skills por chat** — WebSearch, WebFetch, execução Bash
- 🔐 **Backups com criptografia AES-256** de conversas + configurações
- 🌐 **7 idiomas** — inglês, alemão, espanhol, francês, português brasileiro, chinês simplificado, japonês
- 🌗 Design Material You edge-to-edge com temas claro + escuro

## Arquitetura

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

Dois componentes, um repositório:

| Caminho     | O que é                                                                       |
|-------------|-------------------------------------------------------------------------------|
| `server/`   | Backend Python FastAPI + interface web embutida. Inicia o CLI `claude`.       |
| `app/`      | Cliente Android (Kotlin + Jetpack Compose, Material 3).                       |

## Início rápido

### 1 — Instale o servidor (host Linux, ~5 minutos)

Em uma máquina Ubuntu / Debian / Fedora limpa:

```bash
curl -fsSL https://raw.githubusercontent.com/joshtech90/PocketClaude/main/server/deploy/install-linux.sh | sudo bash
```

O instalador cria um usuário de sistema `pocket-claude`, coloca o código em `/opt/pocket-claude/`, instala o CLI do Claude Code, instala as dependências Python em um venv, grava uma unit do systemd e a inicia na porta `8787` (loopback).

### 2 — Faça login no Claude com sua conta Pro/Max

```bash
sudo -u pocket-claude -H claude login
```

O fluxo OAuth roda no seu terminal. Mesmo login que o Claude Code usa.

### 3 — Exponha o servidor com uma URL persistente (Tailscale Funnel, gratuito)

```bash
sudo bash /opt/pocket-claude/deploy/setup-tailscale-funnel.sh
```

Imprime a URL pública quando termina — parece com `https://your-host.your-tailnet.ts.net`. Alternativamente, a pasta deploy também tem um script de configuração de Cloudflare Named Tunnel caso você prefira usar um domínio personalizado.

### 4 — Instale o app Android

Você pode baixar o APK na [release mais recente](https://github.com/joshtech90/PocketClaude/releases) ou compilá-lo você mesmo:

```bash
cd app && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Abra o app → **Adicionar perfil** → insira sua URL + a senha inicial do administrador (impressa no primeiro start do servidor em `/opt/pocket-claude/data/INITIAL_PASSWORD.txt`). Altere a senha no primeiro login.

### 5 — (Opcional) Use a interface web em vez do app

Basta abrir sua URL de túnel em qualquer navegador. A interface web é servida pelo mesmo processo FastAPI.

Configuração completa incluindo o caminho via Cloudflare, migração host-para-host, fluxo de atualização diária e solução de problemas: **[`server/deploy/README.md`](../../server/deploy/README.md)**.

## Multiusuário

Após o primeiro start, um usuário `Admin` é criado e a senha inicial é gravada em `/opt/pocket-claude/data/INITIAL_PASSWORD.txt`. Use a tela **Usuários** no app (apenas admin) para adicionar mais usuários — cada um recebe suas próprias conversas, configurações e chaves de API, todos rodando através da sua única assinatura Pro/Max.

## Leitura em voz alta (TTS)

Três provedores, selecionáveis por usuário:

| Provedor             | Qualidade da voz       | Configuração                  | Custo                                              |
|----------------------|------------------------|-------------------------------|----------------------------------------------------|
| **Microsoft Edge**   | Boa                    | nenhuma                       | gratuito (hospedado pela Microsoft)                |
| **Gemini Direct**    | Excelente              | chave API do AI Studio        | camada gratuita (10 requisições/dia por chave)     |
| **Google Cloud TTS** | Excelente (Chirp 3 HD) | JSON de service account       | 1M caracteres/mês grátis, depois ~$16/M caracteres |

No app: **Configurações → Leitura em voz alta → Provedor** e escolha o que se encaixa. Fala automática, seletores de voz, taxa de fala, pool de múltiplas chaves para a camada gratuita do Gemini Direct.

## Geração de imagens (Gemini Nano Banana)

Traga sua própria chave API do AI Studio (a camada gratuita é suficiente para uso casual). Tela dedicada, galeria, compartilhamento para outros apps, edição imagem para imagem. Desabilitado na interface se nenhuma chave estiver configurada.

## Stack tecnológica

**Servidor.** Python 3.10+, FastAPI, Uvicorn, `sse-starlette` para SSE, aiosqlite, SQLite + FTS5, [`claude-agent-sdk`](https://pypi.org/project/claude-agent-sdk/), `scrypt` (stdlib) para hashing de senha, `pyzipper` para backups AES-256, `edge-tts` + SDK do Google Cloud TTS + REST para Gemini Direct.

**App.** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, compileSdk 35, minSdk 31, Jetpack Compose Material 3 (BOM 2024.12.01), OkHttp + okhttp-sse, DataStore Preferences, Coil 3, AndroidX Media3 (ExoPlayer + MediaSession), `compose-richtext` para Markdown.

**Interface web.** Vanilla JS, sem etapa de build, servida como arquivos estáticos pelo mesmo processo FastAPI.

## Roadmap

- [ ] Cliente iOS (o protocolo já está documentado — contribuições são bem-vindas)
- [ ] Deploy Docker / Docker Compose como alternativa one-liner ao script de instalação no sistema
- [ ] Entrada de voz (baseada em Whisper) no lado do app
- [ ] Orçamentos de ferramentas por conversa

## Contribuindo

Pull requests são bem-vindos — particularmente para traduções (cada `values-XX/strings.xml` e `docs/i18n/README.*.md` é seu próprio pedaço de trabalho com formato de PR), cliente iOS e empacotamento Docker.

Para PRs de tradução: abra um PR por idioma. As strings estão em `app/app/src/main/res/values-XX/strings.xml` e `server/pocket_claude/webui/i18n.js`.

Para contribuições de código:
1. Abra primeiro uma issue para discutir o escopo
2. Mantenha o diff pequeno e focado
3. Mantenha os padrões existentes em vez de introduzir novos

## Licença

MIT — veja [LICENSE](../../LICENSE).

## Agradecimentos

- [Anthropic](https://www.anthropic.com/) pelo Claude e pelo CLI do Claude Code
- [Tailscale](https://tailscale.com/) por tornar os túneis públicos zero-config gratuitos para uso pessoal
- [`claude-agent-sdk`](https://github.com/anthropics/claude-agent-sdk-python) pela superfície limpa de spawn de CLI
- Os times do Compose, FastAPI e SQLite
