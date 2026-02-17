# 📷 NeuralGram — AI Camera for Android

A native Android camera app powered by a custom C neural engine that learns your personal photography style.

## ✨ Features

- **AI Scene Detection** — automatically identifies Portrait, Landscape, Low Light, Sunset, Indoor, and Macro scenes
- **Personal Style Learning** — mark photos as favourites and the AI adapts to your taste over time
- **Native C Processing** — all image enhancement runs in C via JNI (no Java GC pauses = smooth performance)
- **100% Offline** — no internet required, all AI runs on-device for full privacy
- **Adjustable Learning Speed** — slider to control how fast the AI adapts to your style

## 🏗️ Architecture

```
Camera Frame (CameraX)
        ↓
  Kotlin / Java UI
        ↓
    JNI Bridge
        ↓
  neuralgram.c  (Native C AI Engine)
  ├── extract_photo_signature()   — analyses brightness, warmth, saturation etc.
  ├── detect_scene_type()         — classifies scene from signature
  ├── generate_personalized_parameters() — adapts to your style
  └── processImage()              — applies enhancement to frame
```

## 🚀 Building

### Via GitHub Actions (recommended — build on any device)
1. Push this repo to GitHub
2. Go to **Actions** tab
3. The APK builds automatically on every push to `main`
4. Download from the **Releases** page or **Artifacts** section

### Local Build (requires Android Studio + NDK)
```bash
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## 📁 Project Structure

```
neuralgram/
├── .github/workflows/build.yml      # GitHub Actions CI/CD
├── app/
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── neuralgram.c         # AI engine (C, JNI)
│   │   │   └── CMakeLists.txt       # Native build config
│   │   ├── java/com/neuralgram/app/
│   │   │   └── MainActivity.kt      # Camera UI + JNI calls
│   │   ├── res/layout/
│   │   │   └── activity_main.xml    # UI layout
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 🧠 How the AI Works

1. Every photo is analysed into a **10-feature signature** (brightness, warmth, saturation, colour ratios, contrast range)
2. The scene is classified into one of 7 types
3. When you **mark a photo as a favourite**, the AI updates your personal bias using an exponential moving average
4. On the next photo, parameters are personalised based on your learned preferences + current scene

## 📄 License
MIT
