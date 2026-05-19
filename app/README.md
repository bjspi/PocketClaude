# Pocket Claude — Android App

Native Android client for [Pocket Claude](../README.md). Kotlin + Jetpack Compose Material 3, no web wrapper.

For project overview, architecture, and quickstart, see the [top-level README](../README.md).

## Layout

```
app/
├── app/                          The Android module
│   ├── src/main/
│   │   ├── java/de/smartzone/pocketclaude/
│   │   │   ├── MainActivity.kt
│   │   │   ├── data/             Repositories, models, system prompts
│   │   │   ├── ui/               Chat, settings, conversations, images
│   │   │   ├── service/          StreamingService + NotificationHelper
│   │   │   └── ...
│   │   └── res/
│   │       ├── values/strings.xml         English (default)
│   │       ├── values-de/strings.xml      German
│   │       ├── values-es/strings.xml      Spanish
│   │       ├── values-fr/strings.xml      French
│   │       ├── values-pt-rBR/strings.xml  Brazilian Portuguese
│   │       ├── values-zh/strings.xml      Simplified Chinese
│   │       └── values-ja/strings.xml      Japanese
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

## Build

```bash
cd app
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Or directly to a connected device with USB debugging:
./gradlew installDebug
```

## Stack

- **Kotlin 2.0.21**, Android Gradle Plugin 8.7.3, Gradle 8.9
- **Jetpack Compose** Material 3 (BOM 2024.12.01) + Navigation Compose
- **OkHttp + okhttp-sse** for the SSE message stream
- **AndroidX Media3** (ExoPlayer + MediaSession) for TTS playback with lock-screen controls
- **Coil 3** for image loading
- **DataStore Preferences** for settings + locale persistence
- **compose-richtext** for Markdown rendering
- **compileSdk 35**, **minSdk 31** (Android 12+)

## Translations

Strings live in `app/src/main/res/values-XX/strings.xml`. To add a language, copy `values/strings.xml`, translate the contents, and place it in `values-<locale>/strings.xml`. The in-app language picker in **Settings → Appearance → Language** will pick it up automatically.

## License

MIT — see [`../LICENSE`](../LICENSE).
