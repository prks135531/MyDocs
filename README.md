# MyDocs

MyDocs is an Android document scanner and local PDF library.

## Current starter build
- Scan multi-page documents with Google's ML Kit Document Scanner UI.
- Scanner can export PDF and JPEG; PDF is stored in the app's private MyDocs folder.
- Import existing PDFs using Android's document picker.
- List, open, share, rename, and delete saved PDFs.
- No account or cloud service required for this starter version.

## Build APK
GitHub Actions runs on push to `main`/`master` or via **Actions → Build MyDocs APK → Run workflow**.
The generated APK is available under the workflow run's **Artifacts** section as `MyDocs-debug-apk`.

## Build locally
Requires JDK 17, Android SDK 35, and Gradle 8.9:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Planned next modules
Folder management, Trash/restore, PDF merge/split/rotate/reorder, ID-card/passport presets, settings, and local backup/restore are planned follow-up milestones and are not yet implemented in this starter build.
