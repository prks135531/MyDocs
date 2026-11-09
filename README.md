# MyDocs

MyDocs is an Android document scanning and PDF management app.

## Project status

This repository is being rebuilt with a clean Android/Kotlin foundation. The first foundation commit includes:
- Android Gradle project setup
- Kotlin + Jetpack Compose UI
- Initial home, files and tools navigation
- CameraX dependencies for the custom scanner implementation
- GitHub Actions debug APK workflow

## Build

The project uses JDK 17 and Gradle 8.9 or compatible.

```bash
gradle assembleDebug
```

The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

## Planned scanner flow

1. Camera capture screen without the unwanted green overlay.
2. Separate crop and adjustment step.
3. Separate review step with Next navigation.
4. Multi-page capture and document thumbnails.
5. Image enhancement, PDF export and document management.

Package: `com.monu.mydocs`
