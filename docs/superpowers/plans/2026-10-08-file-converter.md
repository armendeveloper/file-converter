# File Converter for Android — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kotlin/Jetpack Compose Android app that converts images, audio, video and documents — media and simple documents offline on the device, office/e-book documents through CloudConvert.

**Architecture:** Single `:app` module. A pure-Kotlin `FormatRegistry` decides which engine handles each (source, target) pair. Five engines implement one `Converter` interface: `ImageConverter` (Android bitmap APIs), `FfmpegConverter` (FFmpegKit fork), `LocalPdfConverter` (`PdfDocument`/`PdfRenderer`), `OfficeLiteConverter` (hand-written OOXML/CSV readers and writers), `CloudConvertConverter` (REST v2). Jobs run in a WorkManager `CoroutineWorker` (foreground, one at a time) and results are saved to `Download/FileConverter/` via MediaStore. UI is Compose + Navigation with manual dependency injection (`AppContainer`).

**Tech Stack:** Kotlin 2.1.20, AGP 8.13.0, Gradle 8.14.3, Compose BOM 2025.06.01 (Material 3), Navigation Compose 2.8.9, WorkManager 2.10.1, Room 2.7.1 (KSP 2.1.20-2.0.1), DataStore 1.1.7, OkHttp 4.12.0, kotlinx.serialization 1.8.1, `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`, JUnit 4 + kxml2 + MockWebServer for JVM tests, AndroidX Test for instrumented tests.

**Spec:** `docs/superpowers/specs/2026-10-08-file-converter-design.md` — read it before starting any task.

## Global Constraints

- **Project root must be a short path.** This machine has `LongPathsEnabled=0`; Gradle/AGP build directories under a long root exceed the 260-character Windows limit. Use e.g. `C:\armen-projects\file-converter` (move the session there before Task 1). All paths below are relative to the project root.
- Package and applicationId: `dev.armen.fileconverter`. Sources live in `app/src/main/java/dev/armen/fileconverter/`, JVM tests in `app/src/test/java/dev/armen/fileconverter/`, instrumented tests in `app/src/androidTest/java/dev/armen/fileconverter/`.
- minSdk 24 / targetSdk 36 / compileSdk 36. ABIs: `arm64-v8a`, `x86_64` only.
- Gradle runs on JDK 21 (`JAVA_HOME` already points at Temurin 21); bytecode target is Java 17. Core library desugaring is enabled (java.time on API 24–25).
- FFmpeg dependency is exactly `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`; its API package is `com.arthenica.ffmpegkit`. GPL-3.0 — personal use only.
- No DI framework (no Hilt/Koin): everything is wired in `AppContainer`.
- Results are saved to `Download/FileConverter/` (multi-file results in `Download/FileConverter/<base name>/`) through MediaStore on API 29+, app-specific `…/files/Download/FileConverter/` + FileProvider on API 24–28.
- CloudConvert: base URL `https://api.cloudconvert.com/v2/`, sandbox `https://api.sandbox.cloudconvert.com/v2/`, header `Authorization: Bearer <key>`, poll every 2 s, max input 1 GB, best-effort `DELETE /jobs/{id}` afterwards.
- Every failure reaches the UI as a `ConversionError` with the spec §6 message; cancellation is silent.
- Only one conversion runs at a time (process-wide `Mutex`).
- JVM tests: `./gradlew testDebugUnitTest`. Instrumented tests: `./gradlew connectedDebugAndroidTest` on AVD `grainlab_test` (API 35, x86_64). Start it with:
  `"$LOCALAPPDATA/Android/Sdk/emulator/emulator" -avd grainlab_test -no-snapshot-save &` then `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" wait-for-device`.
- Shell commands are Git Bash. Run Gradle as `./gradlew …` from the project root.
- Every commit message ends with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Deliberate deviations from the spec (all small; called out so reviewers don't flag them)

1. **Sequential batches use an in-process `Mutex`, not a WorkManager unique-work chain.** In a chain, one failed or cancelled item cancels every later item — including other batches appended to the chain. Workers also always return `Result.success` with error details in the output data, so the UI reads outcomes from output data.
2. **Inputs are copied into app storage when the user taps Convert**, not inside the worker. URI grants from the picker and from share intents can expire before a queued worker starts.
3. **3GP output uses H.264 Baseline + AAC** scaled to fit 352×288 (more compatible than H.263, same size limit).
4. **AAC (`.aac`) is its own audio format** next to M4A.
5. **History entries are removed with a delete button** instead of a swipe gesture (simpler and accessible).

## Review Focus

These inputs are implied by the spec but easy to break; each line names the task whose tests pin it.

1. **File names with accents, emoji, slashes or no extension** (e.g. WhatsApp shares `IMG-2026…` without extension, `Résumé 📄.docx`, `a/b:c?.mp3`) must produce a safe output name and be detected by MIME type — pinned in Task 2 (`detect` MIME fallback) and Task 3 (`FileNamesTest`).
2. **Text files that are not UTF-8** (Notepad UTF-16 with BOM, Windows-1252 `café`) must convert to PDF/DOCX/XLSX without mojibake — pinned in Task 3 (`TextDecodingTest`) and Task 9 (UTF-16 CSV → XLSX).
3. **Video with no audio track** must give a clear "This video has no audio track." error when extracting audio, and stream-copy must not fail on silent video — pinned in Task 4.
4. **CSV cells like `007`, 16-digit IDs, `1e5`** must stay text in XLSX (Excel would mangle them as numbers) — pinned in Task 7 (`XlsxWriterTest`).
5. **Merging 100+ photos into one PDF** must not overflow WorkManager's 10 KB `Data` limit — requests are stored as files; pinned in Task 13 (`largeMergeRequestCanBeEnqueued`) and Task 15 (`buildRequests` merges 150 images into one request).

## File Map

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, .gitignore, local.properties (untracked)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/{values/strings.xml, values/colors.xml, values/themes.xml, xml/file_paths.xml,
                  drawable/ic_launcher_foreground.xml, drawable/ic_stat_convert.xml,
                  mipmap-anydpi/ic_launcher.xml, mipmap-anydpi-v26/ic_launcher.xml}
app/src/main/java/dev/armen/fileconverter/
  FileConverterApp.kt            Application; owns AppContainer
  MainActivity.kt                Compose host; receives share intents
  di/AppContainer.kt             manual DI, converter factory, cloud status
  domain/FileFormat.kt           FileFormat + FormatFamily enums
  domain/Route.kt                Engine, Fidelity, Route
  domain/FormatRegistry.kt       detection, valid targets, engine choice
  domain/ConversionOptions.kt    options + VideoQuality
  domain/ConversionRequest.kt    serializable job description
  domain/ConversionError.kt      ErrorKind + sealed ConversionError
  domain/ErrorMapper.kt          Throwable -> ConversionError
  domain/FileNames.kt            output-name sanitising
  domain/TextDecoding.kt         BOM / UTF-8 / Windows-1252 decoding
  engine/Converter.kt            Converter interface + ConversionTask
  engine/FfmpegCommandBuilder.kt pure FFmpeg argument builder
  engine/FfmpegConverter.kt      FFmpegKit runner (probe, progress, cancel)
  engine/BitmapLoader.kt         decode + EXIF + downscale
  engine/ImageConverter.kt       JPG/PNG/WEBP via Bitmap.compress
  engine/TextPdfWriter.kt        text -> paginated A4 PDF
  engine/LocalPdfConverter.kt    images->PDF, PDF->images, TXT->PDF
  engine/office/XmlSupport.kt    parser factory type, attribute helpers, escapeXml
  engine/office/AndroidXml.kt    Android XmlPullParser factory
  engine/office/OoxmlZip.kt      zip part reader + writer helper
  engine/office/CsvCodec.kt      RFC 4180 CSV
  engine/office/XlsxReader.kt    XLSX -> sheets of strings
  engine/office/XlsxWriter.kt    rows -> XLSX
  engine/office/DocxReader.kt    DOCX -> plain text
  engine/office/DocxWriter.kt    plain text -> DOCX
  engine/office/OfficeLiteConverter.kt  the five offline office pairs
  engine/cloud/CloudConvertModels.kt    DTOs
  engine/cloud/ProgressRequestBody.kt   upload progress
  engine/cloud/CloudConvertClient.kt    REST client
  engine/cloud/CloudConvertConverter.kt job orchestration
  data/SecretCipher.kt           interface + Keystore AES-GCM implementation
  data/SettingsRepository.kt     DataStore settings + encrypted API key
  data/history/HistoryEntry.kt, HistoryDao.kt, HistoryDatabase.kt
  data/InputResolver.kt          Uri -> name/size/MIME; copy to file
  data/OutputStore.kt            save results to Downloads
  data/SelectionHolder.kt        files currently selected in the UI
  work/ConversionRunner.kt       route choice + converter call (JVM-testable)
  work/ConversionWorker.kt       CoroutineWorker
  work/ConversionScheduler.kt    enqueue / observe / cancel batches
  work/ConversionNotifications.kt foreground notification
  work/ConversionLock.kt         process-wide Mutex
  ui/theme/Theme.kt
  ui/Routes.kt, ui/AppNavHost.kt
  ui/common/FileActions.kt, ui/common/Formatting.kt, ui/common/ChoiceChips.kt
  ui/home/HomeViewModel.kt, ui/home/HomeScreen.kt
  ui/settings/SettingsViewModel.kt, ui/settings/SettingsScreen.kt
  ui/convert/ConvertPlanner.kt, ui/convert/ConvertViewModel.kt, ui/convert/ConvertScreen.kt
  ui/batch/BatchMapper.kt, ui/batch/BatchViewModel.kt, ui/batch/ProgressScreen.kt, ui/batch/ResultScreen.kt
README.md
```

---

### Task 1: Project scaffold that builds with FFmpeg

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`, `local.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`, `values/colors.xml`, `values/themes.xml`, `xml/file_paths.xml`, `drawable/ic_launcher_foreground.xml`, `drawable/ic_stat_convert.xml`, `mipmap-anydpi/ic_launcher.xml`, `mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/java/dev/armen/fileconverter/FileConverterApp.kt`, `MainActivity.kt`, `ui/theme/Theme.kt`
- Create (generated): `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Consumes: nothing.
- Produces: a building `:app` module; `FileConverterApp` (empty `Application`, replaced in Task 13); `FileConverterTheme(content)` composable; `R.drawable.ic_stat_convert`; FileProvider authority `"${applicationId}.files"` with `external-files-path name="downloads" path="Download/"`.

- [ ] **Step 1: Write settings, properties and ignore files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "FileConverter"
include(":app")
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`.gitignore`:
```
.gradle/
.kotlin/
build/
local.properties
.idea/
*.iml
captures/
```

- [ ] **Step 2: Generate the Gradle wrapper from the cached 8.14.3 distribution**

```bash
mkdir -p app && touch app/.keep
GRADLE_DIR=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3 | head -1)
"$GRADLE_DIR/bin/gradle" wrapper --gradle-version 8.14.3 --distribution-type bin
rm app/.keep
echo "sdk.dir=$(cygpath -m "$LOCALAPPDATA/Android/Sdk")" > local.properties
```
Expected: `BUILD SUCCESSFUL`; `gradlew`, `gradlew.bat`, `gradle/wrapper/*` exist.

- [ ] **Step 3: Write the version catalog and build scripts**

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.13.0"
kotlin = "2.1.20"
ksp = "2.1.20-2.0.1"
composeBom = "2025.06.01"
coreKtx = "1.16.0"
activityCompose = "1.10.1"
lifecycle = "2.9.1"
navigationCompose = "2.8.9"
work = "2.10.1"
room = "2.7.1"
datastore = "1.1.7"
okhttp = "4.12.0"
serializationJson = "1.8.1"
coroutines = "1.10.2"
exifinterface = "1.4.1"
ffmpegKit = "8.1.9"
desugar = "2.1.5"
junit = "4.13.2"
kxml2 = "2.3.0"
androidxTestExt = "1.2.1"
androidxTestRunner = "1.6.2"
androidxTestCore = "1.6.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-exifinterface = { group = "androidx.exifinterface", name = "exifinterface", version.ref = "exifinterface" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serializationJson" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
ffmpeg-kit = { group = "dev.ffmpegkit-maintained", name = "ffmpeg-kit-full-gpl", version.ref = "ffmpegKit" }
desugar-jdk-libs = { group = "com.android.tools", name = "desugar_jdk_libs", version.ref = "desugar" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kxml2 = { group = "net.sf.kxml", name = "kxml2", version.ref = "kxml2" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExt" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`build.gradle.kts` (root):
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

`app/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.armen.fileconverter"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.armen.fileconverter"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal use: sign release builds with the debug key so they can be sideloaded.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ffmpeg.kit)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
```

- [ ] **Step 4: Write the manifest and resources**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application
        android:name=".FileConverterApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.FileConverter">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="*/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="*/*" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">File Converter</string>
</resources>
```

`app/src/main/res/values/colors.xml`:
```xml
<resources>
    <color name="ic_launcher_background">#1A73E8</color>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.FileConverter" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="downloads" path="Download/" />
</paths>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M38,44h26l-6,-6l3,-3l11,11l-11,11l-3,-3l6,-6h-26z" />
    <path android:fillColor="#FFFFFF" android:pathData="M70,64h-26l6,6l-3,3l-11,-11l11,-11l3,3l-6,6h26z" />
</vector>
```

`app/src/main/res/drawable/ic_stat_convert.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <group android:translateX="-30" android:translateY="-30">
        <path android:fillColor="#FFFFFF" android:pathData="M38,44h26l-6,-6l3,-3l11,11l-11,11l-3,-3l6,-6h-26z" />
        <path android:fillColor="#FFFFFF" android:pathData="M70,64h-26l6,6l-3,3l-11,-11l11,-11l3,3l-6,6h26z" />
    </group>
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/res/mipmap-anydpi/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="oval">
            <solid android:color="@color/ic_launcher_background" />
        </shape>
    </item>
    <item android:drawable="@drawable/ic_launcher_foreground" />
</layer-list>
```

- [ ] **Step 5: Write the Application, Activity and theme**

`app/src/main/java/dev/armen/fileconverter/FileConverterApp.kt`:
```kotlin
package dev.armen.fileconverter

import android.app.Application

class FileConverterApp : Application()
```

`app/src/main/java/dev/armen/fileconverter/ui/theme/Theme.kt`:
```kotlin
package dev.armen.fileconverter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun FileConverterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8AB4F8))
        else -> lightColorScheme(primary = Color(0xFF1A73E8))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
```

`app/src/main/java/dev/armen/fileconverter/MainActivity.kt`:
```kotlin
package dev.armen.fileconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.armen.fileconverter.ui.theme.FileConverterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FileConverterTheme { Surface { Text("File Converter") } } }
    }
}
```

- [ ] **Step 6: Build and verify FFmpeg native libraries are packaged**

Run:
```bash
./gradlew assembleDebug
jar tf app/build/outputs/apk/debug/app-debug.apk | grep -E "lib/(x86_64|arm64-v8a)/libffmpegkit.so"
```
Expected: `BUILD SUCCESSFUL`, and two lines: `lib/arm64-v8a/libffmpegkit.so` and `lib/x86_64/libffmpegkit.so`.
If dependency resolution of `ffmpeg-kit-full-gpl:8.1.9` fails, change `ffmpegKit` to `7.1.7` in the catalog (spec §8 fallback) and re-run.

- [ ] **Step 7: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat app
git commit -m "build: scaffold Android app with Compose and FFmpegKit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Formats and routing (`FormatRegistry`)

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/domain/FileFormat.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/domain/Route.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/domain/FormatRegistry.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/domain/FormatRegistryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class FormatFamily { IMAGE, AUDIO, VIDEO, DOCUMENT }`
  - `enum class FileFormat(val family: FormatFamily, val extensions: List<String>, val mimeTypes: List<String>)` with entries `JPG, PNG, WEBP, BMP, GIF, HEIC, AVIF, TIFF, ICO, MP3, M4A, AAC, WAV, FLAC, OGG, OPUS, AIFF, WMA, MP4, MKV, WEBM, MOV, AVI, THREE_GP, PDF, DOCX, DOC, ODT, RTF, TXT, HTML, MD, XLSX, XLS, ODS, CSV, PPTX, PPT, ODP, EPUB, MOBI, AZW3`; properties `extension: String`, `mimeType: String`, `label: String`.
  - `enum class Engine { IMAGE_NATIVE, FFMPEG, LOCAL_PDF, OFFICE_LITE, CLOUD }`, `enum class Fidelity { FULL, BASIC }`, `data class Route(val engine: Engine, val fidelity: Fidelity = Fidelity.FULL)`
  - `object FormatRegistry { fun detect(fileName: String?, mimeType: String?): FileFormat?; fun routesFor(source: FileFormat, target: FileFormat, sdkInt: Int): List<Route>; fun chooseRoute(source: FileFormat, target: FileFormat, sdkInt: Int, cloudAvailable: Boolean): Route?; fun targetsFor(source: FileFormat, sdkInt: Int): List<FileFormat>; fun isNativelyDecodable(format: FileFormat, sdkInt: Int): Boolean }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/armen/fileconverter/domain/FormatRegistryTest.kt`:
```kotlin
package dev.armen.fileconverter.domain

import dev.armen.fileconverter.domain.FileFormat.AVI
import dev.armen.fileconverter.domain.FileFormat.BMP
import dev.armen.fileconverter.domain.FileFormat.CSV
import dev.armen.fileconverter.domain.FileFormat.DOCX
import dev.armen.fileconverter.domain.FileFormat.GIF
import dev.armen.fileconverter.domain.FileFormat.HEIC
import dev.armen.fileconverter.domain.FileFormat.ICO
import dev.armen.fileconverter.domain.FileFormat.JPG
import dev.armen.fileconverter.domain.FileFormat.MKV
import dev.armen.fileconverter.domain.FileFormat.MOV
import dev.armen.fileconverter.domain.FileFormat.MP3
import dev.armen.fileconverter.domain.FileFormat.MP4
import dev.armen.fileconverter.domain.FileFormat.ODT
import dev.armen.fileconverter.domain.FileFormat.PDF
import dev.armen.fileconverter.domain.FileFormat.PNG
import dev.armen.fileconverter.domain.FileFormat.PPTX
import dev.armen.fileconverter.domain.FileFormat.THREE_GP
import dev.armen.fileconverter.domain.FileFormat.TIFF
import dev.armen.fileconverter.domain.FileFormat.TXT
import dev.armen.fileconverter.domain.FileFormat.WAV
import dev.armen.fileconverter.domain.FileFormat.WEBM
import dev.armen.fileconverter.domain.FileFormat.WEBP
import dev.armen.fileconverter.domain.FileFormat.XLSX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatRegistryTest {
    private val sdk = 34

    @Test fun `detects by extension case-insensitively`() =
        assertEquals(JPG, FormatRegistry.detect("Photo.JPEG", null))

    @Test fun `extension wins over a conflicting mime type`() =
        assertEquals(MP3, FormatRegistry.detect("song.mp3", "video/mp4"))

    @Test fun `falls back to mime when the name has no extension`() =
        assertEquals(PDF, FormatRegistry.detect("IMG-20261008-WA0001", "application/pdf"))

    @Test fun `falls back to mime when the extension is unknown`() =
        assertEquals(MP3, FormatRegistry.detect("track.xyz", "audio/mpeg"))

    @Test fun `ignores mime parameters`() =
        assertEquals(TXT, FormatRegistry.detect(null, "text/plain; charset=utf-8"))

    @Test fun `generic or missing mime without extension is unknown`() {
        assertNull(FormatRegistry.detect("blob", "application/octet-stream"))
        assertNull(FormatRegistry.detect(null, null))
    }

    @Test fun `3gp has a readable label`() = assertEquals("3GP", THREE_GP.label)

    @Test fun `extensions are unique across formats`() {
        val all = FileFormat.entries.flatMap { it.extensions }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun `every format has at least one target`() {
        FileFormat.entries.forEach { assertTrue("$it has no targets", FormatRegistry.targetsFor(it, sdk).isNotEmpty()) }
    }

    @Test fun `a format is never its own target`() {
        FileFormat.entries.forEach { assertFalse(it in FormatRegistry.targetsFor(it, sdk)) }
    }

    @Test fun `audio converts to every other audio format and nothing else`() {
        val expected = FileFormat.entries.filter { it.family == FormatFamily.AUDIO && it != WAV }
        assertEquals(expected, FormatRegistry.targetsFor(WAV, sdk))
    }

    @Test fun `video converts to video, audio and gif`() {
        val targets = FormatRegistry.targetsFor(MP4, sdk)
        assertTrue(targets.containsAll(listOf(MKV, WEBM, MOV, AVI, THREE_GP, MP3, WAV, GIF)))
        assertFalse(PDF in targets)
        assertFalse(PNG in targets)
    }

    @Test fun `png targets`() =
        assertEquals(listOf(JPG, WEBP, BMP, GIF, TIFF, ICO, PDF), FormatRegistry.targetsFor(PNG, sdk))

    @Test fun `heic decodes natively from API 28`() {
        assertEquals(Route(Engine.IMAGE_NATIVE), FormatRegistry.chooseRoute(HEIC, JPG, 28, cloudAvailable = false))
        assertEquals(Route(Engine.FFMPEG), FormatRegistry.chooseRoute(HEIC, JPG, 27, cloudAvailable = false))
        assertTrue(PDF in FormatRegistry.targetsFor(HEIC, 28))
        assertFalse(PDF in FormatRegistry.targetsFor(HEIC, 27))
    }

    @Test fun `tiff uses ffmpeg and cannot become pdf`() {
        assertEquals(Route(Engine.FFMPEG), FormatRegistry.chooseRoute(TIFF, PNG, sdk, cloudAvailable = false))
        assertFalse(PDF in FormatRegistry.targetsFor(TIFF, sdk))
    }

    @Test fun `bmp output always uses ffmpeg`() =
        assertEquals(Route(Engine.FFMPEG), FormatRegistry.chooseRoute(JPG, BMP, sdk, cloudAvailable = false))

    @Test fun `txt to pdf stays on device even when cloud is available`() =
        assertEquals(Route(Engine.LOCAL_PDF), FormatRegistry.chooseRoute(TXT, PDF, sdk, cloudAvailable = true))

    @Test fun `docx to pdf prefers cloud and falls back to basic offline`() {
        assertEquals(Route(Engine.CLOUD), FormatRegistry.chooseRoute(DOCX, PDF, sdk, cloudAvailable = true))
        assertEquals(Route(Engine.OFFICE_LITE, Fidelity.BASIC), FormatRegistry.chooseRoute(DOCX, PDF, sdk, cloudAvailable = false))
    }

    @Test fun `cloud-only pair has no route without cloud`() {
        assertEquals(listOf(Route(Engine.CLOUD)), FormatRegistry.routesFor(DOCX, ODT, sdk))
        assertNull(FormatRegistry.chooseRoute(DOCX, ODT, sdk, cloudAvailable = false))
    }

    @Test fun `office lite pairs are always offline`() {
        listOf(XLSX to CSV, CSV to XLSX, DOCX to TXT, TXT to DOCX).forEach { (s, t) ->
            assertEquals("$s->$t", Route(Engine.OFFICE_LITE), FormatRegistry.chooseRoute(s, t, sdk, cloudAvailable = true))
        }
    }

    @Test fun `pdf to images is on device`() =
        assertEquals(Route(Engine.LOCAL_PDF), FormatRegistry.chooseRoute(PDF, PNG, sdk, cloudAvailable = true))

    @Test fun `no route across document groups`() =
        assertTrue(FormatRegistry.routesFor(PPTX, DOCX, sdk).isEmpty())

    @Test fun `audio cannot become a document`() =
        assertTrue(FormatRegistry.routesFor(MP3, PDF, sdk).isEmpty())
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.domain.FormatRegistryTest"`
Expected: FAIL — compilation errors `Unresolved reference: FormatRegistry` / `FileFormat`.

- [ ] **Step 3: Implement the formats, routes and registry**

`app/src/main/java/dev/armen/fileconverter/domain/FileFormat.kt`:
```kotlin
package dev.armen.fileconverter.domain

import dev.armen.fileconverter.domain.FormatFamily.AUDIO
import dev.armen.fileconverter.domain.FormatFamily.DOCUMENT
import dev.armen.fileconverter.domain.FormatFamily.IMAGE
import dev.armen.fileconverter.domain.FormatFamily.VIDEO

enum class FormatFamily { IMAGE, AUDIO, VIDEO, DOCUMENT }

enum class FileFormat(
    val family: FormatFamily,
    val extensions: List<String>,
    val mimeTypes: List<String>,
) {
    JPG(IMAGE, listOf("jpg", "jpeg"), listOf("image/jpeg")),
    PNG(IMAGE, listOf("png"), listOf("image/png")),
    WEBP(IMAGE, listOf("webp"), listOf("image/webp")),
    BMP(IMAGE, listOf("bmp"), listOf("image/bmp", "image/x-ms-bmp")),
    GIF(IMAGE, listOf("gif"), listOf("image/gif")),
    HEIC(IMAGE, listOf("heic", "heif"), listOf("image/heic", "image/heif")),
    AVIF(IMAGE, listOf("avif"), listOf("image/avif")),
    TIFF(IMAGE, listOf("tiff", "tif"), listOf("image/tiff")),
    ICO(IMAGE, listOf("ico"), listOf("image/x-icon", "image/vnd.microsoft.icon")),

    MP3(AUDIO, listOf("mp3"), listOf("audio/mpeg", "audio/mp3")),
    M4A(AUDIO, listOf("m4a"), listOf("audio/mp4", "audio/x-m4a", "audio/m4a")),
    AAC(AUDIO, listOf("aac"), listOf("audio/aac", "audio/x-aac", "audio/aacp")),
    WAV(AUDIO, listOf("wav"), listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")),
    FLAC(AUDIO, listOf("flac"), listOf("audio/flac", "audio/x-flac")),
    OGG(AUDIO, listOf("ogg", "oga"), listOf("audio/ogg", "application/ogg", "audio/vorbis")),
    OPUS(AUDIO, listOf("opus"), listOf("audio/opus")),
    AIFF(AUDIO, listOf("aiff", "aif"), listOf("audio/aiff", "audio/x-aiff")),
    WMA(AUDIO, listOf("wma"), listOf("audio/x-ms-wma")),

    MP4(VIDEO, listOf("mp4", "m4v"), listOf("video/mp4")),
    MKV(VIDEO, listOf("mkv"), listOf("video/x-matroska")),
    WEBM(VIDEO, listOf("webm"), listOf("video/webm")),
    MOV(VIDEO, listOf("mov"), listOf("video/quicktime")),
    AVI(VIDEO, listOf("avi"), listOf("video/x-msvideo", "video/avi")),
    THREE_GP(VIDEO, listOf("3gp"), listOf("video/3gpp")),

    PDF(DOCUMENT, listOf("pdf"), listOf("application/pdf")),
    DOCX(DOCUMENT, listOf("docx"), listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    DOC(DOCUMENT, listOf("doc"), listOf("application/msword")),
    ODT(DOCUMENT, listOf("odt"), listOf("application/vnd.oasis.opendocument.text")),
    RTF(DOCUMENT, listOf("rtf"), listOf("application/rtf", "text/rtf")),
    TXT(DOCUMENT, listOf("txt"), listOf("text/plain")),
    HTML(DOCUMENT, listOf("html", "htm"), listOf("text/html")),
    MD(DOCUMENT, listOf("md", "markdown"), listOf("text/markdown", "text/x-markdown")),
    XLSX(DOCUMENT, listOf("xlsx"), listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    XLS(DOCUMENT, listOf("xls"), listOf("application/vnd.ms-excel")),
    ODS(DOCUMENT, listOf("ods"), listOf("application/vnd.oasis.opendocument.spreadsheet")),
    CSV(DOCUMENT, listOf("csv"), listOf("text/csv", "text/comma-separated-values")),
    PPTX(DOCUMENT, listOf("pptx"), listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    PPT(DOCUMENT, listOf("ppt"), listOf("application/vnd.ms-powerpoint")),
    ODP(DOCUMENT, listOf("odp"), listOf("application/vnd.oasis.opendocument.presentation")),
    EPUB(DOCUMENT, listOf("epub"), listOf("application/epub+zip")),
    MOBI(DOCUMENT, listOf("mobi"), listOf("application/x-mobipocket-ebook")),
    AZW3(DOCUMENT, listOf("azw3"), listOf("application/vnd.amazon.ebook")),
    ;

    /** Extension used for output files and for CloudConvert format names. */
    val extension: String get() = extensions.first()
    val mimeType: String get() = mimeTypes.first()
    val label: String get() = if (this == THREE_GP) "3GP" else name
}
```

`app/src/main/java/dev/armen/fileconverter/domain/Route.kt`:
```kotlin
package dev.armen.fileconverter.domain

enum class Engine { IMAGE_NATIVE, FFMPEG, LOCAL_PDF, OFFICE_LITE, CLOUD }

/** BASIC routes lose content (e.g. DOCX -> PDF without layout) and are only a fallback. */
enum class Fidelity { FULL, BASIC }

data class Route(val engine: Engine, val fidelity: Fidelity = Fidelity.FULL)
```

`app/src/main/java/dev/armen/fileconverter/domain/FormatRegistry.kt`:
```kotlin
package dev.armen.fileconverter.domain

import dev.armen.fileconverter.domain.FileFormat.AVIF
import dev.armen.fileconverter.domain.FileFormat.AZW3
import dev.armen.fileconverter.domain.FileFormat.BMP
import dev.armen.fileconverter.domain.FileFormat.CSV
import dev.armen.fileconverter.domain.FileFormat.DOC
import dev.armen.fileconverter.domain.FileFormat.DOCX
import dev.armen.fileconverter.domain.FileFormat.EPUB
import dev.armen.fileconverter.domain.FileFormat.GIF
import dev.armen.fileconverter.domain.FileFormat.HEIC
import dev.armen.fileconverter.domain.FileFormat.HTML
import dev.armen.fileconverter.domain.FileFormat.ICO
import dev.armen.fileconverter.domain.FileFormat.JPG
import dev.armen.fileconverter.domain.FileFormat.MD
import dev.armen.fileconverter.domain.FileFormat.MOBI
import dev.armen.fileconverter.domain.FileFormat.ODP
import dev.armen.fileconverter.domain.FileFormat.ODS
import dev.armen.fileconverter.domain.FileFormat.ODT
import dev.armen.fileconverter.domain.FileFormat.PDF
import dev.armen.fileconverter.domain.FileFormat.PNG
import dev.armen.fileconverter.domain.FileFormat.PPT
import dev.armen.fileconverter.domain.FileFormat.PPTX
import dev.armen.fileconverter.domain.FileFormat.RTF
import dev.armen.fileconverter.domain.FileFormat.TIFF
import dev.armen.fileconverter.domain.FileFormat.TXT
import dev.armen.fileconverter.domain.FileFormat.WEBP
import dev.armen.fileconverter.domain.FileFormat.XLS
import dev.armen.fileconverter.domain.FileFormat.XLSX

object FormatRegistry {

    private val GENERIC_MIME = setOf("application/octet-stream", "*/*", "application/x-binary", "application/unknown")

    /** Extension first (most specific); MIME type only when the name has no known extension. */
    fun detect(fileName: String?, mimeType: String?): FileFormat? {
        val ext = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
        if (ext.isNotEmpty()) {
            FileFormat.entries.firstOrNull { ext in it.extensions }?.let { return it }
        }
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime.isNullOrEmpty() || mime in GENERIC_MIME) return null
        return FileFormat.entries.firstOrNull { mime in it.mimeTypes }
    }

    fun targetsFor(source: FileFormat, sdkInt: Int): List<FileFormat> =
        FileFormat.entries.filter { routesFor(source, it, sdkInt).isNotEmpty() }

    fun chooseRoute(source: FileFormat, target: FileFormat, sdkInt: Int, cloudAvailable: Boolean): Route? =
        routesFor(source, target, sdkInt).firstOrNull { it.engine != Engine.CLOUD || cloudAvailable }

    /** All routes for a pair, most preferred first: on-device FULL, then CLOUD, then on-device BASIC. */
    fun routesFor(source: FileFormat, target: FileFormat, sdkInt: Int): List<Route> {
        if (source == target) return emptyList()
        return when (source.family) {
            FormatFamily.IMAGE -> imageRoutes(source, target, sdkInt)
            FormatFamily.AUDIO ->
                if (target.family == FormatFamily.AUDIO) listOf(Route(Engine.FFMPEG)) else emptyList()
            FormatFamily.VIDEO ->
                if (target.family == FormatFamily.VIDEO || target.family == FormatFamily.AUDIO || target == GIF) {
                    listOf(Route(Engine.FFMPEG))
                } else {
                    emptyList()
                }
            FormatFamily.DOCUMENT -> documentRoutes(source, target)
        }
    }

    fun isNativelyDecodable(format: FileFormat, sdkInt: Int): Boolean = when (format) {
        JPG, PNG, WEBP, BMP, GIF, ICO -> true
        HEIC -> sdkInt >= 28
        AVIF -> sdkInt >= 31
        else -> false
    }

    private val NATIVE_IMAGE_OUTPUTS = setOf(JPG, PNG, WEBP)
    private val FFMPEG_IMAGE_OUTPUTS = setOf(BMP, TIFF, ICO, GIF)

    private fun imageRoutes(source: FileFormat, target: FileFormat, sdkInt: Int): List<Route> {
        val native = isNativelyDecodable(source, sdkInt)
        return when {
            target in NATIVE_IMAGE_OUTPUTS -> listOf(Route(if (native) Engine.IMAGE_NATIVE else Engine.FFMPEG))
            target in FFMPEG_IMAGE_OUTPUTS -> listOf(Route(Engine.FFMPEG))
            target == PDF && native -> listOf(Route(Engine.LOCAL_PDF))
            else -> emptyList()
        }
    }

    private val CLOUD_GROUPS: List<Set<FileFormat>> = listOf(
        setOf(DOCX, DOC, ODT, RTF, TXT, HTML, MD, PDF),
        setOf(XLSX, XLS, ODS, CSV, PDF),
        setOf(PPTX, PPT, ODP, PDF),
        setOf(EPUB, MOBI, AZW3, PDF),
    )

    private val LOCAL_FULL: Map<Pair<FileFormat, FileFormat>, Engine> = mapOf(
        (PDF to PNG) to Engine.LOCAL_PDF,
        (PDF to JPG) to Engine.LOCAL_PDF,
        (TXT to PDF) to Engine.LOCAL_PDF,
        (XLSX to CSV) to Engine.OFFICE_LITE,
        (CSV to XLSX) to Engine.OFFICE_LITE,
        (DOCX to TXT) to Engine.OFFICE_LITE,
        (TXT to DOCX) to Engine.OFFICE_LITE,
    )

    private val LOCAL_BASIC: Map<Pair<FileFormat, FileFormat>, Engine> = mapOf(
        (DOCX to PDF) to Engine.OFFICE_LITE,
    )

    private fun documentRoutes(source: FileFormat, target: FileFormat): List<Route> = buildList {
        LOCAL_FULL[source to target]?.let { add(Route(it)) }
        if (CLOUD_GROUPS.any { source in it && target in it }) add(Route(Engine.CLOUD))
        LOCAL_BASIC[source to target]?.let { add(Route(it, Fidelity.BASIC)) }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.domain.FormatRegistryTest"`
Expected: PASS (23 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/domain app/src/test/java/dev/armen/fileconverter/domain
git commit -m "feat: add file formats and conversion routing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Requests, options, errors and shared helpers

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/domain/ConversionOptions.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/domain/ConversionRequest.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/domain/ConversionError.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/domain/ErrorMapper.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/domain/FileNames.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/domain/TextDecoding.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/Converter.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/domain/ConversionRequestTest.kt`, `ErrorMapperTest.kt`, `FileNamesTest.kt`, `TextDecodingTest.kt`

**Interfaces:**
- Consumes: `FileFormat`, `FormatFamily` (Task 2).
- Produces:
  - `enum class VideoQuality(val x264Crf: Int, val vp9Crf: Int, val xvidQ: Int) { HIGH, MEDIUM, SMALL }`
  - `data class ConversionOptions(imageQuality: Int = 90, maxDimension: Int? = null, audioBitrateKbps: Int = 192, sampleRateHz: Int? = null, channels: Int? = null, videoHeight: Int? = null, videoQuality: VideoQuality? = null, trimStartMs: Long? = null, trimEndMs: Long? = null, gifFps: Int = 10, gifWidth: Int = 480)` + companion lists `AUDIO_BITRATES`, `SAMPLE_RATES`, `MAX_DIMENSIONS`, `VIDEO_HEIGHTS`, `GIF_FPS`, `GIF_WIDTHS`, `MAX_GIF_RANGE_MS = 30_000L`
  - `data class ConversionInput(val path: String, val displayName: String)`; `data class ConversionRequest(val inputs: List<ConversionInput>, val source: FileFormat, val target: FileFormat, val options: ConversionOptions = ConversionOptions())` with `toJson(): String` and `ConversionRequest.fromJson(json: String)`
  - `enum class ErrorKind { UNSUPPORTED_PAIR, UNREADABLE_INPUT, MISSING_API_KEY, INVALID_API_KEY, QUOTA_EXCEEDED, NO_NETWORK, INSUFFICIENT_STORAGE, ENGINE_FAILED }`
  - `sealed class ConversionError(val kind: ErrorKind, message: String, val details: String?, cause: Throwable?) : Exception` with subclasses `UnsupportedPair(source, target)`, `UnreadableInput(format: FileFormat?, cause: Throwable? = null)`, `MissingApiKey()`, `InvalidApiKey()`, `QuotaExceeded()`, `NoNetwork(cause: Throwable? = null)`, `InsufficientStorage(cause: Throwable? = null)`, `EngineFailed(details: String?, cause: Throwable? = null)`
  - `object ErrorMapper { fun map(t: Throwable): ConversionError }`
  - `object FileNames { fun baseName(displayName: String): String; fun sanitize(name: String): String; fun withExtension(base: String, format: FileFormat): String }`
  - `object TextDecoding { fun decode(bytes: ByteArray): String }`
  - `data class ConversionTask(val inputs: List<File>, val source: FileFormat, val target: FileFormat, val options: ConversionOptions, val outputDir: File, val baseName: String)`; `interface Converter { suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> }` (a plain interface: suspend members in `fun interface` are not relied on)

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/armen/fileconverter/domain/ConversionRequestTest.kt`:
```kotlin
package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionRequestTest {
    @Test fun `round trips through json`() {
        val request = ConversionRequest(
            inputs = listOf(ConversionInput("/data/q/0-a.mp4", "a.mp4"), ConversionInput("/data/q/1-b.mp4", "b é.mp4")),
            source = FileFormat.MP4,
            target = FileFormat.GIF,
            options = ConversionOptions(trimStartMs = 1_000, trimEndMs = 4_000, videoQuality = VideoQuality.HIGH, gifFps = 15),
        )
        assertEquals(request, ConversionRequest.fromJson(request.toJson()))
    }

    @Test fun `ignores unknown keys`() {
        val json = """{"inputs":[{"path":"/p","displayName":"n.txt"}],"source":"TXT","target":"PDF","future":true}"""
        assertEquals(FileFormat.PDF, ConversionRequest.fromJson(json).target)
    }
}
```

`app/src/test/java/dev/armen/fileconverter/domain/ErrorMapperTest.kt`:
```kotlin
package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class ErrorMapperTest {
    @Test fun `conversion errors pass through unchanged`() {
        val error = ConversionError.MissingApiKey()
        assertSame(error, ErrorMapper.map(error))
    }

    @Test fun `dns and connect failures are no network`() {
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.map(UnknownHostException("api.cloudconvert.com")).kind)
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.map(ConnectException("refused")).kind)
    }

    @Test fun `ENOSPC is insufficient storage, also when nested`() {
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, ErrorMapper.map(IOException("write failed: ENOSPC (No space left on device)")).kind)
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, ErrorMapper.map(IOException("copy failed", IOException("No space left on device"))).kind)
    }

    @Test fun `other io errors are engine failures with details`() {
        val mapped = ErrorMapper.map(IOException("broken pipe"))
        assertEquals(ErrorKind.ENGINE_FAILED, mapped.kind)
        assertEquals("broken pipe", mapped.details)
    }

    @Test fun `out of memory is an engine failure`() {
        val mapped = ErrorMapper.map(OutOfMemoryError())
        assertEquals(ErrorKind.ENGINE_FAILED, mapped.kind)
        assertTrue(mapped.details!!.contains("too large"))
    }

    @Test fun `exception without message uses its class name`() =
        assertEquals("IllegalStateException", ErrorMapper.map(IllegalStateException()).details)
}
```

`app/src/test/java/dev/armen/fileconverter/domain/FileNamesTest.kt`:
```kotlin
package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test fun `strips only the last extension`() = assertEquals("My Report.final", FileNames.baseName("My Report.final.docx"))
    @Test fun `replaces characters illegal in file names`() = assertEquals("a_b_c_", FileNames.baseName("a/b:c?.mp3"))
    @Test fun `keeps accents and emoji`() = assertEquals("Résumé 📄", FileNames.baseName("Résumé 📄.docx"))
    @Test fun `removes control characters`() = assertEquals("ab", FileNames.sanitize("a\u0001b"))
    @Test fun `hidden or empty names fall back`() {
        assertEquals("converted", FileNames.baseName(".hidden"))
        assertEquals("converted", FileNames.sanitize("   "))
    }
    @Test fun `long names are truncated to 100 characters`() = assertEquals(100, FileNames.sanitize("x".repeat(300)).length)
    @Test fun `name without extension is kept`() = assertEquals("IMG-20261008-WA0001", FileNames.baseName("IMG-20261008-WA0001"))
    @Test fun `adds the target extension`() = assertEquals("clip.3gp", FileNames.withExtension("clip", FileFormat.THREE_GP))
}
```

`app/src/test/java/dev/armen/fileconverter/domain/TextDecodingTest.kt`:
```kotlin
package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecodingTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun `utf8 with bom`() = assertEquals("hé", TextDecoding.decode(bytes(0xEF, 0xBB, 0xBF) + "hé".toByteArray(Charsets.UTF_8)))
    @Test fun `utf16 little endian with bom`() = assertEquals("hé", TextDecoding.decode(bytes(0xFF, 0xFE) + "hé".toByteArray(Charsets.UTF_16LE)))
    @Test fun `utf16 big endian with bom`() = assertEquals("hé", TextDecoding.decode(bytes(0xFE, 0xFF) + "hé".toByteArray(Charsets.UTF_16BE)))
    @Test fun `plain utf8`() = assertEquals("naïve ✓", TextDecoding.decode("naïve ✓".toByteArray(Charsets.UTF_8)))
    @Test fun `invalid utf8 falls back to windows-1252`() = assertEquals("café", TextDecoding.decode(bytes(0x63, 0x61, 0x66, 0xE9)))
    @Test fun `empty input`() = assertEquals("", TextDecoding.decode(ByteArray(0)))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.domain.*"`
Expected: FAIL — `Unresolved reference: ConversionRequest`, `ErrorMapper`, `FileNames`, `TextDecoding`.

- [ ] **Step 3: Implement**

`app/src/main/java/dev/armen/fileconverter/domain/ConversionOptions.kt`:
```kotlin
package dev.armen.fileconverter.domain

import kotlinx.serialization.Serializable

@Serializable
enum class VideoQuality(val x264Crf: Int, val vp9Crf: Int, val xvidQ: Int) {
    HIGH(20, 28, 3),
    MEDIUM(23, 33, 5),
    SMALL(28, 38, 8),
}

/**
 * Every option a conversion can use. Engines ignore options that do not apply to them.
 * `videoQuality == null` means "keep": stream-copy when the codecs allow it.
 */
@Serializable
data class ConversionOptions(
    val imageQuality: Int = 90,
    val maxDimension: Int? = null,
    val audioBitrateKbps: Int = 192,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val videoHeight: Int? = null,
    val videoQuality: VideoQuality? = null,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val gifFps: Int = 10,
    val gifWidth: Int = 480,
) {
    companion object {
        val AUDIO_BITRATES = listOf(64, 128, 192, 256, 320)
        val SAMPLE_RATES = listOf(44_100, 48_000)
        val MAX_DIMENSIONS = listOf(4096, 2048, 1080, 720)
        val VIDEO_HEIGHTS = listOf(1080, 720, 480)
        val GIF_FPS = listOf(10, 15)
        val GIF_WIDTHS = listOf(320, 480, 720)
        const val MAX_GIF_RANGE_MS = 30_000L
    }
}
```

`app/src/main/java/dev/armen/fileconverter/domain/ConversionRequest.kt`:
```kotlin
package dev.armen.fileconverter.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [path] is a file in app storage (copied from the user's Uri before enqueueing). */
@Serializable
data class ConversionInput(val path: String, val displayName: String)

@Serializable
data class ConversionRequest(
    val inputs: List<ConversionInput>,
    val source: FileFormat,
    val target: FileFormat,
    val options: ConversionOptions = ConversionOptions(),
) {
    fun toJson(): String = RequestJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): ConversionRequest = RequestJson.decodeFromString(serializer(), json)
    }
}

private val RequestJson = Json { ignoreUnknownKeys = true }
```

`app/src/main/java/dev/armen/fileconverter/domain/ConversionError.kt`:
```kotlin
package dev.armen.fileconverter.domain

enum class ErrorKind {
    UNSUPPORTED_PAIR, UNREADABLE_INPUT, MISSING_API_KEY, INVALID_API_KEY,
    QUOTA_EXCEEDED, NO_NETWORK, INSUFFICIENT_STORAGE, ENGINE_FAILED,
}

/** Every failure shown to the user. [message] is the user-facing text from spec §6. */
sealed class ConversionError(
    val kind: ErrorKind,
    message: String,
    val details: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class UnsupportedPair(source: FileFormat, target: FileFormat) :
        ConversionError(ErrorKind.UNSUPPORTED_PAIR, "Can't convert ${source.label} to ${target.label}.")

    class UnreadableInput(format: FileFormat?, cause: Throwable? = null) : ConversionError(
        ErrorKind.UNREADABLE_INPUT,
        "This file looks damaged or isn't really a ${format?.label ?: "supported"} file.",
        cause?.message,
        cause,
    )

    class MissingApiKey : ConversionError(ErrorKind.MISSING_API_KEY, "Add your CloudConvert API key in Settings.")

    class InvalidApiKey : ConversionError(ErrorKind.INVALID_API_KEY, "Your CloudConvert key was rejected. Check it in Settings.")

    class QuotaExceeded : ConversionError(ErrorKind.QUOTA_EXCEEDED, "CloudConvert limit reached — try later or upgrade your plan.")

    class NoNetwork(cause: Throwable? = null) :
        ConversionError(ErrorKind.NO_NETWORK, "No internet connection.", cause?.message, cause)

    class InsufficientStorage(cause: Throwable? = null) :
        ConversionError(ErrorKind.INSUFFICIENT_STORAGE, "Not enough storage space.", cause?.message, cause)

    class EngineFailed(details: String?, cause: Throwable? = null) :
        ConversionError(ErrorKind.ENGINE_FAILED, "Conversion failed.", details, cause)
}
```

`app/src/main/java/dev/armen/fileconverter/domain/ErrorMapper.kt`:
```kotlin
package dev.armen.fileconverter.domain

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns any throwable into a [ConversionError]. Callers must rethrow CancellationException themselves. */
object ErrorMapper {
    fun map(t: Throwable): ConversionError = when (t) {
        is ConversionError -> t
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
            ConversionError.NoNetwork(t)
        is IOException -> if (isNoSpace(t)) ConversionError.InsufficientStorage(t) else ConversionError.EngineFailed(t.message, t)
        is OutOfMemoryError -> ConversionError.EngineFailed("Out of memory: the file is too large to process on this device.", t)
        else -> ConversionError.EngineFailed(t.message ?: t::class.java.simpleName, t)
    }

    private fun isNoSpace(t: Throwable): Boolean {
        val messages = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return "ENOSPC" in messages || "No space left" in messages
    }
}
```

`app/src/main/java/dev/armen/fileconverter/domain/FileNames.kt`:
```kotlin
package dev.armen.fileconverter.domain

object FileNames {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")
    private const val MAX_LENGTH = 100

    /** "My Report.final.docx" -> "My Report.final", sanitised. */
    fun baseName(displayName: String): String =
        sanitize(displayName.substringBeforeLast('.', missingDelimiterValue = displayName))

    /** Drops control characters, replaces characters illegal in file names with '_'. */
    fun sanitize(name: String): String =
        ILLEGAL.replace(CONTROL.replace(name, ""), "_").trim().trim('.').take(MAX_LENGTH).ifBlank { "converted" }

    fun withExtension(base: String, format: FileFormat): String = "$base.${format.extension}"
}
```

`app/src/main/java/dev/armen/fileconverter/domain/TextDecoding.kt`:
```kotlin
package dev.armen.fileconverter.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decodes text files from the wild: BOM-marked UTF-8/16, plain UTF-8, else Windows-1252. */
object TextDecoding {
    fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        isValidUtf8(bytes) -> String(bytes, Charsets.UTF_8)
        else -> String(bytes, Charset.forName("windows-1252"))
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}
```

`app/src/main/java/dev/armen/fileconverter/engine/Converter.kt`:
```kotlin
package dev.armen.fileconverter.engine

import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import java.io.File

/**
 * One unit of work for an engine. [inputs] has one file, except images merged into one PDF.
 * Outputs must be written into [outputDir]; [baseName] is the sanitised name without extension.
 */
data class ConversionTask(
    val inputs: List<File>,
    val source: FileFormat,
    val target: FileFormat,
    val options: ConversionOptions,
    val outputDir: File,
    val baseName: String,
)

interface Converter {
    /** Returns the produced files. Reports progress in 0f..1f. Throws ConversionError on failure. */
    suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File>
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.domain.*"`
Expected: PASS (FormatRegistryTest + 4 new classes).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/domain app/src/main/java/dev/armen/fileconverter/engine app/src/test/java/dev/armen/fileconverter/domain
git commit -m "feat: add conversion requests, options, errors and helpers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: FFmpeg command builder

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/FfmpegCommandBuilder.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/engine/FfmpegCommandBuilderTest.kt`

**Interfaces:**
- Consumes: `FileFormat`, `FormatFamily`, `ConversionOptions`, `VideoQuality`, `ConversionError` (Tasks 2–3).
- Produces:
  - `data class MediaProbe(val durationMs: Long? = null, val videoCodec: String? = null, val audioCodec: String? = null, val width: Int? = null, val height: Int? = null)`
  - `data class FfmpegCommand(val args: List<String>, val expectedDurationMs: Long?)`
  - `object FfmpegCommandBuilder { const val DEFAULT_GIF_MS = 5_000L; fun build(input: String, output: String, source: FileFormat, target: FileFormat, options: ConversionOptions, probe: MediaProbe): FfmpegCommand }` — throws `ConversionError.UnsupportedPair` / `ConversionError.EngineFailed`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/armen/fileconverter/engine/FfmpegCommandBuilderTest.kt`:
```kotlin
package dev.armen.fileconverter.engine

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {
    private val prefix = listOf("-y", "-hide_banner", "-nostdin")
    private val audioProbe = MediaProbe(durationMs = 2_000, audioCodec = "pcm_s16le")
    private val h264Probe = MediaProbe(durationMs = 60_000, videoCodec = "h264", audioCodec = "aac", width = 1920, height = 1080)
    private val gifFilter = "fps=10,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse"

    private fun build(
        source: FileFormat,
        target: FileFormat,
        options: ConversionOptions = ConversionOptions(),
        probe: MediaProbe = MediaProbe(),
    ) = FfmpegCommandBuilder.build("in.${source.extension}", "out.${target.extension}", source, target, options, probe)

    private fun assertContainsSequence(args: List<String>, sequence: List<String>) =
        assertTrue("$sequence not in $args", args.windowed(sequence.size).any { it == sequence })

    @Test fun `wav to mp3 at 320 kbps`() {
        val command = build(FileFormat.WAV, FileFormat.MP3, ConversionOptions(audioBitrateKbps = 320), audioProbe)
        assertEquals(prefix + listOf("-i", "in.wav", "-vn", "-c:a", "libmp3lame", "-b:a", "320k", "out.mp3"), command.args)
        assertEquals(2_000L, command.expectedDurationMs)
    }

    @Test fun `opus always uses 48 kHz`() {
        val args = build(FileFormat.MP3, FileFormat.OPUS, ConversionOptions(sampleRateHz = 44_100), audioProbe).args
        assertContainsSequence(args, listOf("-c:a", "libopus"))
        assertContainsSequence(args, listOf("-ar", "48000"))
        assertFalse("44100" in args)
    }

    @Test fun `m4a with sample rate, mono and faststart`() {
        val args = build(FileFormat.FLAC, FileFormat.M4A, ConversionOptions(sampleRateHz = 44_100, channels = 1), audioProbe).args
        assertEquals(
            prefix + listOf("-i", "in.flac", "-vn", "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "1", "-movflags", "+faststart", "out.m4a"),
            args,
        )
    }

    @Test fun `lossless and pcm targets have no bitrate`() {
        assertContainsSequence(build(FileFormat.MP3, FileFormat.WAV, probe = audioProbe).args, listOf("-c:a", "pcm_s16le"))
        assertContainsSequence(build(FileFormat.MP3, FileFormat.FLAC, probe = audioProbe).args, listOf("-c:a", "flac"))
        assertContainsSequence(build(FileFormat.MP3, FileFormat.AIFF, probe = audioProbe).args, listOf("-c:a", "pcm_s16be"))
        assertFalse("-b:a" in build(FileFormat.MP3, FileFormat.WAV, probe = audioProbe).args)
    }

    @Test fun `video to m4a extracts the audio track`() {
        val args = build(FileFormat.MP4, FileFormat.M4A, probe = h264Probe).args
        assertContainsSequence(args, listOf("-vn", "-c:a", "aac"))
    }

    @Test fun `video without audio cannot become audio`() {
        val error = assertThrows(ConversionError.EngineFailed::class.java) {
            build(FileFormat.MP4, FileFormat.MP3, probe = h264Probe.copy(audioCodec = null))
        }
        assertEquals("This video has no audio track.", error.details)
    }

    @Test fun `compatible codecs are stream-copied`() {
        val command = build(FileFormat.MOV, FileFormat.MP4, probe = h264Probe)
        assertEquals(
            prefix + listOf("-i", "in.mov", "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", "out.mp4"),
            command.args,
        )
        assertEquals(60_000L, command.expectedDurationMs)
    }

    @Test fun `silent video is stream-copied with optional audio map`() {
        val args = build(FileFormat.MP4, FileFormat.MKV, probe = h264Probe.copy(audioCodec = null)).args
        assertContainsSequence(args, listOf("-map", "0:a:0?", "-c", "copy"))
    }

    @Test fun `incompatible codecs are re-encoded for webm`() {
        val args = build(FileFormat.MP4, FileFormat.WEBM, probe = h264Probe).args
        assertContainsSequence(args, listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "33"))
        assertContainsSequence(args, listOf("-c:a", "libopus"))
        assertFalse("copy" in args)
    }

    @Test fun `downscales when the source is taller than the chosen height`() {
        val args = build(FileFormat.MOV, FileFormat.MP4, ConversionOptions(videoHeight = 720), h264Probe).args
        assertContainsSequence(args, listOf("-vf", "scale=-2:720"))
        assertContainsSequence(args, listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23"))
    }

    @Test fun `never upscales and keeps stream copy`() {
        val args = build(FileFormat.MP4, FileFormat.MKV, ConversionOptions(videoHeight = 720), h264Probe.copy(height = 480)).args
        assertContainsSequence(args, listOf("-c", "copy"))
        assertFalse("-movflags" in args)
    }

    @Test fun `chosen quality forces re-encoding`() {
        val args = build(FileFormat.MOV, FileFormat.MP4, ConversionOptions(videoQuality = VideoQuality.HIGH), h264Probe).args
        assertContainsSequence(args, listOf("-crf", "20"))
        assertContainsSequence(args, listOf("-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2"))
    }

    @Test fun `avi uses xvid and mp3`() {
        val args = build(FileFormat.MP4, FileFormat.AVI, probe = h264Probe).args
        assertContainsSequence(args, listOf("-c:v", "libxvid", "-q:v", "5", "-c:a", "libmp3lame"))
    }

    @Test fun `3gp is always re-encoded to fit 352x288`() {
        val args = build(FileFormat.MP4, FileFormat.THREE_GP, probe = h264Probe).args
        assertContainsSequence(args, listOf("-vf", "scale=w=352:h=288:force_original_aspect_ratio=decrease:force_divisible_by=2"))
        assertContainsSequence(args, listOf("-c:v", "libx264", "-profile:v", "baseline"))
    }

    @Test fun `trimmed video seeks before input and limits duration`() {
        val command = build(FileFormat.MP4, FileFormat.MKV, ConversionOptions(trimStartMs = 1_500, trimEndMs = 4_000), h264Probe)
        assertEquals(prefix + listOf("-ss", "1.500", "-i", "in.mp4", "-t", "2.500"), command.args.take(9))
        assertContainsSequence(command.args, listOf("-c:v", "libx264"))
        assertEquals(2_500L, command.expectedDurationMs)
    }

    @Test fun `audio trimmed from the start only`() {
        val command = build(FileFormat.WAV, FileFormat.MP3, ConversionOptions(trimStartMs = 1_000), audioProbe)
        assertEquals(prefix + listOf("-ss", "1.000", "-i", "in.wav"), command.args.take(7))
        assertFalse("-t" in command.args)
        assertEquals(1_000L, command.expectedDurationMs)
    }

    @Test fun `trim end before start is rejected`() {
        assertThrows(ConversionError.EngineFailed::class.java) {
            build(FileFormat.MP4, FileFormat.MKV, ConversionOptions(trimStartMs = 5_000, trimEndMs = 2_000), h264Probe)
        }
    }

    @Test fun `gif defaults to the first five seconds`() {
        val command = build(FileFormat.MP4, FileFormat.GIF, probe = h264Probe)
        assertEquals(prefix + listOf("-i", "in.mp4", "-t", "5.000", "-vf", gifFilter, "-loop", "0", "-an", "out.gif"), command.args)
        assertEquals(5_000L, command.expectedDurationMs)
    }

    @Test fun `gif range is clamped to 30 seconds`() {
        val args = build(FileFormat.MP4, FileFormat.GIF, ConversionOptions(trimStartMs = 10_000, trimEndMs = 60_000), h264Probe).args
        assertContainsSequence(args, listOf("-ss", "10.000", "-i", "in.mp4", "-t", "30.000"))
    }

    @Test fun `gif range is clamped to the video duration`() {
        val args = build(FileFormat.MP4, FileFormat.GIF, probe = h264Probe.copy(durationMs = 3_000)).args
        assertContainsSequence(args, listOf("-t", "3.000"))
    }

    @Test fun `tiff to jpg at best quality`() {
        val command = build(FileFormat.TIFF, FileFormat.JPG, ConversionOptions(imageQuality = 100), MediaProbe(width = 800, height = 600))
        assertEquals(prefix + listOf("-i", "in.tiff", "-frames:v", "1", "-q:v", "2", "out.jpg"), command.args)
        assertNull(command.expectedDurationMs)
    }

    @Test fun `ico is scaled to fit 256 pixels`() {
        val args = build(FileFormat.PNG, FileFormat.ICO, probe = MediaProbe(width = 1024, height = 512)).args
        assertEquals(
            prefix + listOf("-i", "in.png", "-vf", "scale=w=256:h=256:force_original_aspect_ratio=decrease", "-frames:v", "1", "out.ico"),
            args,
        )
    }

    @Test fun `webp quality and resize`() {
        val args = build(FileFormat.TIFF, FileFormat.WEBP, ConversionOptions(imageQuality = 80, maxDimension = 2048), MediaProbe(width = 4000, height = 3000)).args
        assertContainsSequence(args, listOf("-vf", "scale=w=2048:h=2048:force_original_aspect_ratio=decrease"))
        assertContainsSequence(args, listOf("-c:v", "libwebp", "-quality", "80"))
    }

    @Test fun `small images are not upscaled`() {
        val args = build(FileFormat.TIFF, FileFormat.PNG, ConversionOptions(maxDimension = 4096), MediaProbe(width = 800, height = 600)).args
        assertFalse("-vf" in args)
    }

    @Test fun `audio cannot become video`() {
        assertThrows(ConversionError.UnsupportedPair::class.java) { build(FileFormat.MP3, FileFormat.MP4, probe = audioProbe) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.FfmpegCommandBuilderTest"`
Expected: FAIL — `Unresolved reference: FfmpegCommandBuilder` / `MediaProbe`.

- [ ] **Step 3: Implement the builder**

`app/src/main/java/dev/armen/fileconverter/engine/FfmpegCommandBuilder.kt`:
```kotlin
package dev.armen.fileconverter.engine

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.VideoQuality
import java.util.Locale

/** What FFprobe found out about the input. All fields are null when probing failed. */
data class MediaProbe(
    val durationMs: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** [expectedDurationMs] is the output duration used to turn FFmpeg's time statistics into progress. */
data class FfmpegCommand(val args: List<String>, val expectedDurationMs: Long?)

/** Pure function from a conversion description to FFmpeg arguments. */
object FfmpegCommandBuilder {
    const val DEFAULT_GIF_MS = 5_000L

    private data class TrimRange(val startMs: Long, val endMs: Long?)

    fun build(
        input: String,
        output: String,
        source: FileFormat,
        target: FileFormat,
        options: ConversionOptions,
        probe: MediaProbe,
    ): FfmpegCommand {
        if (source.family == FormatFamily.VIDEO && target.family == FormatFamily.AUDIO &&
            probe.videoCodec != null && probe.audioCodec == null
        ) {
            throw ConversionError.EngineFailed("This video has no audio track.")
        }
        val range = trimRange(source, target, options, probe)
        val args = mutableListOf("-y", "-hide_banner", "-nostdin")
        if (range != null && range.startMs > 0) args += listOf("-ss", seconds(range.startMs))
        args += listOf("-i", input)
        if (range?.endMs != null) args += listOf("-t", seconds(range.endMs - range.startMs))
        args += when {
            target.family == FormatFamily.AUDIO &&
                (source.family == FormatFamily.AUDIO || source.family == FormatFamily.VIDEO) -> audioArgs(target, options)
            source.family == FormatFamily.VIDEO && target == FileFormat.GIF -> gifArgs(options)
            source.family == FormatFamily.VIDEO && target.family == FormatFamily.VIDEO ->
                videoArgs(target, options, probe, trimmed = range != null)
            source.family == FormatFamily.IMAGE && target.family == FormatFamily.IMAGE -> imageArgs(target, options, probe)
            else -> throw ConversionError.UnsupportedPair(source, target)
        }
        args += output
        val expected = when {
            source.family == FormatFamily.IMAGE -> null
            range?.endMs != null -> range.endMs - range.startMs
            range != null -> probe.durationMs?.let { it - range.startMs }
            else -> probe.durationMs
        }
        return FfmpegCommand(args, expected)
    }

    private fun trimRange(source: FileFormat, target: FileFormat, o: ConversionOptions, probe: MediaProbe): TrimRange? {
        if (source.family == FormatFamily.VIDEO && target == FileFormat.GIF) {
            val start = (o.trimStartMs ?: 0L).coerceAtLeast(0L)
            var end = o.trimEndMs ?: (start + DEFAULT_GIF_MS)
            end = minOf(end, start + ConversionOptions.MAX_GIF_RANGE_MS)
            probe.durationMs?.let { end = minOf(end, it) }
            if (end <= start) throw ConversionError.EngineFailed("The GIF range is empty. Pick an end time after the start time.")
            return TrimRange(start, end)
        }
        if (source.family != FormatFamily.AUDIO && source.family != FormatFamily.VIDEO) return null
        if (o.trimStartMs == null && o.trimEndMs == null) return null
        val start = (o.trimStartMs ?: 0L).coerceAtLeast(0L)
        val end = o.trimEndMs
        if (end != null && end <= start) throw ConversionError.EngineFailed("The trim end must be after the trim start.")
        return TrimRange(start, end)
    }

    private fun audioArgs(target: FileFormat, o: ConversionOptions): List<String> = buildList {
        add("-vn")
        val bitrate = "${o.audioBitrateKbps}k"
        addAll(
            when (target) {
                FileFormat.MP3 -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
                FileFormat.M4A, FileFormat.AAC -> listOf("-c:a", "aac", "-b:a", bitrate)
                FileFormat.WAV -> listOf("-c:a", "pcm_s16le")
                FileFormat.FLAC -> listOf("-c:a", "flac")
                FileFormat.OGG -> listOf("-c:a", "libvorbis", "-b:a", bitrate)
                FileFormat.OPUS -> listOf("-c:a", "libopus", "-b:a", bitrate)
                FileFormat.AIFF -> listOf("-c:a", "pcm_s16be")
                FileFormat.WMA -> listOf("-c:a", "wmav2", "-b:a", bitrate)
                else -> error("$target is not an audio format")
            },
        )
        // libopus only accepts 8/12/16/24/48 kHz.
        val rate = if (target == FileFormat.OPUS) 48_000 else o.sampleRateHz
        rate?.let { addAll(listOf("-ar", it.toString())) }
        o.channels?.let { addAll(listOf("-ac", it.toString())) }
        if (target == FileFormat.M4A) addAll(listOf("-movflags", "+faststart"))
    }

    private fun gifArgs(o: ConversionOptions): List<String> = listOf(
        "-vf", "fps=${o.gifFps},scale=${o.gifWidth}:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
        "-loop", "0",
        "-an",
    )

    private val COPY_VIDEO_CODECS = mapOf(
        FileFormat.MP4 to setOf("h264", "hevc", "mpeg4", "av1"),
        FileFormat.MOV to setOf("h264", "hevc", "mpeg4", "prores"),
        FileFormat.MKV to setOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg4"),
        FileFormat.WEBM to setOf("vp8", "vp9", "av1"),
        FileFormat.AVI to setOf("mpeg4", "h264", "mjpeg"),
    )

    private val COPY_AUDIO_CODECS = mapOf(
        FileFormat.MP4 to setOf("aac", "mp3", "alac", "ac3"),
        FileFormat.MOV to setOf("aac", "mp3", "alac", "pcm_s16le"),
        FileFormat.MKV to setOf("aac", "mp3", "opus", "vorbis", "flac", "ac3", "pcm_s16le"),
        FileFormat.WEBM to setOf("opus", "vorbis"),
        FileFormat.AVI to setOf("mp3", "ac3", "pcm_s16le"),
    )

    private fun canStreamCopy(target: FileFormat, probe: MediaProbe): Boolean {
        val video = probe.videoCodec ?: return false
        if (video !in COPY_VIDEO_CODECS[target].orEmpty()) return false
        val audio = probe.audioCodec ?: return true
        return audio in COPY_AUDIO_CODECS[target].orEmpty()
    }

    private fun videoArgs(target: FileFormat, o: ConversionOptions, probe: MediaProbe, trimmed: Boolean): List<String> {
        val maxHeight = o.videoHeight
        val sourceHeight = probe.height
        val downscale = maxHeight != null && sourceHeight != null && sourceHeight > maxHeight
        val maps = listOf("-map", "0:v:0", "-map", "0:a:0?")
        val reencode = target == FileFormat.THREE_GP || trimmed || downscale || o.videoQuality != null ||
            !canStreamCopy(target, probe)
        if (!reencode) return maps + listOf("-c", "copy") + muxerFlags(target)

        val q = o.videoQuality ?: VideoQuality.MEDIUM
        val filter = when {
            target == FileFormat.THREE_GP -> "scale=w=352:h=288:force_original_aspect_ratio=decrease:force_divisible_by=2"
            downscale -> "scale=-2:$maxHeight"
            else -> "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        }
        val codecs = when (target) {
            FileFormat.MP4, FileFormat.MOV, FileFormat.MKV -> listOf(
                "-c:v", "libx264", "-preset", "veryfast", "-crf", q.x264Crf.toString(), "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "160k",
            )
            FileFormat.WEBM -> listOf(
                "-c:v", "libvpx-vp9", "-b:v", "0", "-crf", q.vp9Crf.toString(), "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
                "-c:a", "libopus", "-b:a", "128k",
            )
            FileFormat.AVI -> listOf("-c:v", "libxvid", "-q:v", q.xvidQ.toString(), "-c:a", "libmp3lame", "-b:a", "192k")
            FileFormat.THREE_GP -> listOf(
                "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.0", "-crf", q.x264Crf.toString(), "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k",
            )
            else -> error("$target is not a video format")
        }
        return maps + listOf("-vf", filter) + codecs + muxerFlags(target)
    }

    private fun muxerFlags(target: FileFormat): List<String> =
        if (target == FileFormat.MP4 || target == FileFormat.MOV) listOf("-movflags", "+faststart") else emptyList()

    private fun imageArgs(target: FileFormat, o: ConversionOptions, probe: MediaProbe): List<String> = buildList {
        val longest = maxOf(probe.width ?: 0, probe.height ?: 0)
        val limit = if (target == FileFormat.ICO) minOf(256, o.maxDimension ?: 256) else o.maxDimension
        if (limit != null && (longest > limit || (target == FileFormat.ICO && longest == 0))) {
            addAll(listOf("-vf", "scale=w=$limit:h=$limit:force_original_aspect_ratio=decrease"))
        }
        addAll(listOf("-frames:v", "1"))
        when (target) {
            FileFormat.JPG -> addAll(listOf("-q:v", jpegQScale(o.imageQuality).toString()))
            FileFormat.WEBP -> addAll(listOf("-c:v", "libwebp", "-quality", o.imageQuality.toString()))
            else -> Unit
        }
    }

    /** Maps quality 1..100 to FFmpeg's MJPEG qscale 31..2 (lower is better). */
    private fun jpegQScale(quality: Int): Int = (2 + (100 - quality.coerceIn(1, 100)) * 29 / 99).coerceIn(2, 31)

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.FfmpegCommandBuilderTest"`
Expected: PASS (25 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine/FfmpegCommandBuilder.kt app/src/test/java/dev/armen/fileconverter/engine
git commit -m "feat: build FFmpeg commands for audio, video, gif and images

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: FFmpeg converter (runs FFmpegKit)

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/FfmpegConverter.kt`
- Test: `app/src/androidTest/java/dev/armen/fileconverter/engine/TestMedia.kt`
- Test: `app/src/androidTest/java/dev/armen/fileconverter/engine/FfmpegConverterTest.kt`

**Interfaces:**
- Consumes: `Converter`, `ConversionTask` (Task 3), `FfmpegCommandBuilder`, `MediaProbe`, `FfmpegCommand` (Task 4), `FileNames`, `ConversionError`.
- Produces: `class FfmpegConverter : Converter` (no constructor args). Output file: `File(task.outputDir, "${task.baseName}.${task.target.extension}")`. Cancelling the coroutine cancels the FFmpeg session. Instrumented-test helper `object TestMedia { fun wav(dir, name = "tone.wav", seconds = 2, rate = 44_100): File; fun png(dir, name, width = 320, height = 240, color = ..., alpha = false): File; fun mp4(dir, withAudio = true): File; fun header(file, count): ByteArray }` — reused by Task 10.

Instrumented tests need the emulator running (see Global Constraints).

- [ ] **Step 1: Write the failing instrumented test and its fixture helper**

`app/src/androidTest/java/dev/armen/fileconverter/engine/TestMedia.kt`:
```kotlin
package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** Generates small media fixtures on the device so the repo needs no binary test files. */
object TestMedia {
    fun wav(dir: File, name: String = "tone.wav", seconds: Int = 2, rate: Int = 44_100): File {
        val samples = seconds * rate
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) pcm.putShort((sin(2 * PI * 440 * i / rate) * 12_000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + samples * 2); put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(samples * 2)
        }
        return File(dir, name).apply { writeBytes(header.array() + pcm.array()) }
    }

    fun png(
        dir: File,
        name: String,
        width: Int = 320,
        height: Int = 240,
        color: Int = Color.rgb(30, 120, 220),
        alpha: Boolean = false,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (alpha) {
            bitmap.eraseColor(Color.TRANSPARENT)
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { this.color = color })
        } else {
            canvas.drawColor(color)
        }
        return File(dir, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun mp4(dir: File, withAudio: Boolean = true): File {
        val frames = File(dir, "frames").apply { mkdirs() }
        repeat(30) { i -> png(frames, String.format(Locale.US, "f%03d.png", i), color = Color.rgb(i * 8, 100, 255 - i * 8)) }
        val out = File(dir, if (withAudio) "clip.mp4" else "silent.mp4")
        val args = mutableListOf("-y", "-framerate", "15", "-i", File(frames, "f%03d.png").absolutePath)
        if (withAudio) args += listOf("-i", wav(dir, "clip-audio.wav").absolutePath)
        args += listOf("-c:v", "libx264", "-pix_fmt", "yuv420p")
        if (withAudio) args += listOf("-c:a", "aac", "-shortest")
        args += out.absolutePath
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        check(ReturnCode.isSuccess(session.returnCode)) { session.allLogsAsString }
        return out
    }

    fun header(file: File, count: Int): ByteArray {
        val buffer = ByteArray(count)
        file.inputStream().use { input ->
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n < 0) break
                read += n
            }
        }
        return buffer
    }
}
```

`app/src/androidTest/java/dev/armen/fileconverter/engine/FfmpegConverterTest.kt`:
```kotlin
package dev.armen.fileconverter.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FfmpegConverterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File
    private val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    @Before fun setUp() { dir = File(context.cacheDir, "ffmpeg-test-${System.nanoTime()}").apply { mkdirs() } }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun convert(input: File, source: FileFormat, target: FileFormat, options: ConversionOptions = ConversionOptions()): File =
        runBlocking {
            val out = File(dir, "out").apply { mkdirs() }
            FfmpegConverter().convert(ConversionTask(listOf(input), source, target, options, out, "result")) {}.single()
        }

    private fun ascii(file: File, from: Int, count: Int) =
        String(TestMedia.header(file, from + count), from, count, Charsets.US_ASCII)

    @Test fun wavToMp3() {
        val out = convert(TestMedia.wav(dir), FileFormat.WAV, FileFormat.MP3)
        assertEquals("result.mp3", out.name)
        val head = TestMedia.header(out, 3)
        assertTrue(String(head, Charsets.US_ASCII) == "ID3" || (head[0].toInt() and 0xFF) == 0xFF)
    }

    @Test fun mp3ToOgg() {
        val mp3 = convert(TestMedia.wav(dir), FileFormat.WAV, FileFormat.MP3)
        assertEquals("OggS", ascii(convert(mp3, FileFormat.MP3, FileFormat.OGG), 0, 4))
    }

    @Test fun mp4ToWebm() =
        assertArrayEquals(ebml, TestMedia.header(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.WEBM), 4))

    @Test fun mp4ToMkvStreamCopies() =
        assertArrayEquals(ebml, TestMedia.header(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.MKV), 4))

    @Test fun mp4ToGif() =
        assertEquals("GIF89a", ascii(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.GIF, ConversionOptions(trimEndMs = 1_000)), 0, 6))

    @Test fun mp4ToM4a() =
        assertEquals("ftyp", ascii(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.M4A), 4, 4))

    @Test fun pngToBmp() =
        assertEquals("BM", ascii(convert(TestMedia.png(dir, "a.png"), FileFormat.PNG, FileFormat.BMP), 0, 2))

    @Test fun pngToIcoIsAtMost256px() {
        val ico = convert(TestMedia.png(dir, "big.png", width = 1024, height = 512), FileFormat.PNG, FileFormat.ICO)
        val head = TestMedia.header(ico, 8)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), head.copyOfRange(0, 4))
        assertEquals(0, head[6].toInt() and 0xFF) // 0 means 256 px wide
        assertEquals(128, head[7].toInt() and 0xFF)
    }

    @Test fun silentVideoToMp3FailsClearly() {
        val silent = TestMedia.mp4(dir, withAudio = false)
        val error = assertThrows(ConversionError.EngineFailed::class.java) { convert(silent, FileFormat.MP4, FileFormat.MP3) }
        assertEquals("This video has no audio track.", error.details)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Start the emulator (Global Constraints), then run:
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.armen.fileconverter.engine.FfmpegConverterTest`
Expected: FAIL — compilation error `Unresolved reference: FfmpegConverter`.

- [ ] **Step 3: Implement the converter**

`app/src/main/java/dev/armen/fileconverter/engine/FfmpegConverter.kt`:
```kotlin
package dev.armen.fileconverter.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio, video, GIF and FFmpeg-only image conversions. Cancelling the coroutine cancels FFmpeg. */
class FfmpegConverter : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val input = task.inputs.single()
            val output = File(task.outputDir, FileNames.withExtension(task.baseName, task.target))
            val probe = probe(input)
            val command = FfmpegCommandBuilder.build(
                input.absolutePath, output.absolutePath, task.source, task.target, task.options, probe,
            )
            execute(command, onProgress)
            if (!output.exists() || output.length() == 0L) throw ConversionError.EngineFailed("FFmpeg produced no output.")
            onProgress(1f)
            listOf(output)
        }

    private fun probe(file: File): MediaProbe {
        val info = FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation ?: return MediaProbe()
        val streams = info.streams.orEmpty()
        val video = streams.firstOrNull { it.type == "video" }
        val audio = streams.firstOrNull { it.type == "audio" }
        return MediaProbe(
            durationMs = info.duration?.toDoubleOrNull()?.let { (it * 1000).toLong() },
            videoCodec = video?.codec,
            audioCodec = audio?.codec,
            width = video?.width?.toInt(),
            height = video?.height?.toInt(),
        )
    }

    private suspend fun execute(command: FfmpegCommand, onProgress: (Float) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val expected = command.expectedDurationMs
            val session = FFmpegKit.executeWithArgumentsAsync(
                command.args.toTypedArray(),
                FFmpegSessionCompleteCallback { finished ->
                    val code = finished.returnCode
                    when {
                        ReturnCode.isSuccess(code) -> cont.resume(Unit)
                        ReturnCode.isCancel(code) -> cont.cancel()
                        else -> cont.resumeWithException(ConversionError.EngineFailed(lastLines(finished.allLogsAsString)))
                    }
                },
                null,
                StatisticsCallback { stats ->
                    if (expected != null && expected > 0) onProgress((stats.time / expected).toFloat().coerceIn(0f, 0.99f))
                },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private fun lastLines(log: String?): String =
        log.orEmpty().lines().filter { it.isNotBlank() }.takeLast(20).joinToString("\n")
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.armen.fileconverter.engine.FfmpegConverterTest`
Expected: PASS (9 tests). If an encoder is missing from the AAR (log says `Unknown encoder`), stop and report which one — do not silently change codecs.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine/FfmpegConverter.kt app/src/androidTest/java/dev/armen/fileconverter/engine
git commit -m "feat: run FFmpeg conversions with progress and cancellation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: CSV codec

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/CsvCodec.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/engine/office/CsvCodecTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object CsvCodec { fun detectDelimiter(text: String): Char; fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>>; fun write(rows: List<List<String>>, delimiter: Char = ','): String }` — `write` uses CRLF line endings; `parse` strips a leading BOM.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/armen/fileconverter/engine/office/CsvCodecTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvCodecTest {
    @Test fun `parses simple rows`() =
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), CsvCodec.parse("a,b\n1,2\n"))

    @Test fun `keeps empty fields`() =
        assertEquals(listOf(listOf("a", "", "b", "")), CsvCodec.parse("a,,b,"))

    @Test fun `handles quoted delimiters, escaped quotes and newlines`() =
        assertEquals(
            listOf(listOf("x, y", "say \"hi\"", "line1\nline2")),
            CsvCodec.parse("\"x, y\",\"say \"\"hi\"\"\",\"line1\nline2\"\r\n"),
        )

    @Test fun `handles CRLF`() = assertEquals(listOf(listOf("a"), listOf("b")), CsvCodec.parse("a\r\nb\r\n"))

    @Test fun `strips a byte order mark`() = assertEquals(listOf(listOf("id")), CsvCodec.parse("﻿id"))

    @Test fun `detects semicolons and prefers them on ties`() {
        assertEquals(';', CsvCodec.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals(listOf(listOf("1,5", "2")), CsvCodec.parse("1,5;2"))
    }

    @Test fun `detects tabs`() = assertEquals('\t', CsvCodec.detectDelimiter("a\tb\n"))

    @Test fun `ignores delimiters inside quotes when detecting`() =
        assertEquals(',', CsvCodec.detectDelimiter("\"a;b;c\",d\n"))

    @Test fun `defaults to comma`() = assertEquals(',', CsvCodec.detectDelimiter("single"))

    @Test fun `empty input has no rows`() = assertEquals(emptyList<List<String>>(), CsvCodec.parse(""))

    @Test fun `writes with quoting where needed`() =
        assertEquals(
            "a,\"b,c\",\"d\"\"e\"\r\n\" f\",\"x\ny\"\r\n",
            CsvCodec.write(listOf(listOf("a", "b,c", "d\"e"), listOf(" f", "x\ny"))),
        )

    @Test fun `round trips`() {
        val rows = listOf(listOf("id", "note"), listOf("1", "He said \"ok\", then left\nfor good"), listOf("2", ""))
        assertEquals(rows, CsvCodec.parse(CsvCodec.write(rows)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.CsvCodecTest"`
Expected: FAIL — `Unresolved reference: CsvCodec`.

- [ ] **Step 3: Implement the codec**

`app/src/main/java/dev/armen/fileconverter/engine/office/CsvCodec.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

/** RFC 4180 CSV with delimiter detection. Lenient: a quote inside an unquoted field is kept literally. */
object CsvCodec {
    // Order matters: on a tie the earlier candidate wins (";" CSVs often use "," as decimal mark).
    private val CANDIDATES = listOf(';', '\t', ',')

    fun detectDelimiter(text: String): Char {
        val record = firstRecord(text.removePrefix("﻿"))
        val best = CANDIDATES.maxBy { countOutsideQuotes(record, it) }
        return if (countOutsideQuotes(record, best) == 0) ',' else best
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val s = text.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(ch)
                    delimiter -> endField()
                    '\r' -> {
                        if (i + 1 < s.length && s[i + 1] == '\n') i++
                        endRow()
                    }
                    '\n' -> endRow()
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun write(rows: List<List<String>>, delimiter: Char = ','): String = buildString {
        rows.forEach { row ->
            row.joinTo(this, delimiter.toString()) { quote(it, delimiter) }
            append("\r\n")
        }
    }

    private fun quote(value: String, delimiter: Char): String {
        val needsQuotes = value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } ||
            value.startsWith(' ') || value.endsWith(' ')
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun firstRecord(text: String): String {
        var inQuotes = false
        for ((index, ch) in text.withIndex()) {
            if (ch == '"') inQuotes = !inQuotes
            else if ((ch == '\n' || ch == '\r') && !inQuotes) return text.substring(0, index)
        }
        return text
    }

    private fun countOutsideQuotes(record: String, delimiter: Char): Int {
        var inQuotes = false
        var count = 0
        for (ch in record) {
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == delimiter && !inQuotes) count++
        }
        return count
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.CsvCodecTest"`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine/office/CsvCodec.kt app/src/test/java/dev/armen/fileconverter/engine/office/CsvCodecTest.kt
git commit -m "feat: add RFC 4180 CSV codec with delimiter detection

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: XLSX reader and writer

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/XmlSupport.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/AndroidXml.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/OoxmlZip.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/XlsxReader.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/XlsxWriter.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/engine/office/OfficeTestSupport.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/engine/office/XlsxReaderTest.kt`, `XlsxWriterTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `typealias XmlParserFactory = () -> XmlPullParser`; `object AndroidXml { val factory: XmlParserFactory }`
  - `internal fun XmlPullParser.attr(localName: String): String?`, `internal fun XmlPullParser.relationshipId(): String?`, `internal fun escapeXml(text: String): String`
  - `internal object OoxmlZip { fun read(file: File, wanted: (String) -> Boolean): Map<String, ByteArray> }`, `internal fun ZipOutputStream.putText(name: String, text: String)`
  - `data class Sheet(val name: String, val rows: List<List<String>>)`
  - `class XlsxReader(newParser: XmlParserFactory) { fun read(file: File): List<Sheet> }` (throws `IOException` for non-XLSX); companion: `isDateFormat(id: Int, custom: Map<Int, String>): Boolean`, `serialToIso(serial: Double, date1904: Boolean): String`, `columnIndex(ref: String): Int?`
  - `class XlsxWriter { fun write(rows: List<List<String>>, out: File, sheetName: String = "Sheet1") }`; companion: `columnName(index: Int): String`, `safeSheetName(name: String): String`
  - Test helpers (used by Tasks 8–9): `val kxmlFactory: XmlParserFactory`, `fun writeZip(file: File, entries: Map<String, String>)`, `fun readZipEntry(file: File, name: String): String`, `fun writeExcelFixture(file: File)`, `fun writeMinimalWorkbook(file: File, sheetData: String, numFmtIds: List<Int> = listOf(0), date1904: Boolean = false)`

- [ ] **Step 1: Write the test helpers and failing tests**

`app/src/test/java/dev/armen/fileconverter/engine/office/OfficeTestSupport.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** kxml2 is the same parser Android ships; created directly so the android.jar stubs are never touched. */
val kxmlFactory: XmlParserFactory = {
    KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
}

fun writeZip(file: File, entries: Map<String, String>) {
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}

fun readZipEntry(file: File, name: String): String =
    ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8) }

private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

/** A workbook shaped like Excel's output: shared strings, rich text, phonetic runs, dates, prefixes. */
fun writeExcelFixture(file: File) = writeZip(
    file,
    mapOf(
        "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
        "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$MAIN" xmlns:r="$REL"><workbookPr/><sheets><sheet name="People" sheetId="1" r:id="rId1"/><sheet name="Q&amp;A" sheetId="2" r:id="rId2"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG_REL"><Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$REL/worksheet" Target="/xl/worksheets/sheet2.xml"/><Relationship Id="rId3" Type="$REL/styles" Target="styles.xml"/></Relationships>""",
        "xl/sharedStrings.xml" to """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$MAIN" count="3" uniqueCount="3"><si><t>Name</t></si><si><t>Joined</t></si><si><r><t xml:space="preserve">Ann </t></r><r><rPr><b/></rPr><t>Lee</t></r><rPh sb="0" eb="1"><t>アン</t></rPh></si></sst>""",
        "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$MAIN"><numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd"/></numFmts><cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="14" applyNumberFormat="1"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="2"/></cellXfs></styleSheet>""",
        "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="$MAIN"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>Score</t></is></c></row><row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" s="1"><v>45306</v></c><c r="C2" s="3"><v>98.5</v></c></row><row r="3"><c r="A3" t="b"><v>1</v></c><c r="C3" t="str"><f>"4"&amp;"2"</f><v>42</v></c></row><row r="5"><c r="A5" t="e"><v>#DIV/0!</v></c><c r="D5" s="2"><v>45306.4375</v></c></row></sheetData></worksheet>""",
        "xl/worksheets/sheet2.xml" to """<x:worksheet xmlns:x="$MAIN"><x:sheetData><x:row><x:c t="inlineStr"><x:is><x:t>x</x:t></x:is></x:c><x:c><x:v>7</x:v></x:c></x:row></x:sheetData></x:worksheet>""",
    ),
)

fun writeMinimalWorkbook(file: File, sheetData: String, numFmtIds: List<Int> = listOf(0), date1904: Boolean = false) = writeZip(
    file,
    mapOf(
        "xl/workbook.xml" to """<workbook xmlns="$MAIN" xmlns:r="$REL"><workbookPr${if (date1904) " date1904=\"1\"" else ""}/><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="$PKG_REL"><Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/styles.xml" to """<styleSheet xmlns="$MAIN"><cellXfs>${numFmtIds.joinToString("") { "<xf numFmtId=\"$it\"/>" }}</cellXfs></styleSheet>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$MAIN"><sheetData>$sheetData</sheetData></worksheet>""",
    ),
)
```

`app/src/test/java/dev/armen/fileconverter/engine/office/XlsxReaderTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class XlsxReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val reader = XlsxReader(kxmlFactory)

    @Test fun `reads sheets the way Excel writes them`() {
        val file = tmp.newFile("book.xlsx").also(::writeExcelFixture)
        val sheets = reader.read(file)
        assertEquals(listOf("People", "Q&A"), sheets.map { it.name })
        assertEquals(
            listOf(
                listOf("Name", "Joined", "Score"),
                listOf("Ann Lee", "2024-01-15", "98.5"),
                listOf("TRUE", "", "42"),
                emptyList(),
                listOf("#DIV/0!", "", "", "2024-01-15T10:30:00"),
            ),
            sheets[0].rows,
        )
        assertEquals(listOf(listOf("x", "7")), sheets[1].rows)
    }

    @Test fun `uses the 1904 date system when the workbook says so`() {
        val file = tmp.newFile("mac.xlsx")
        writeMinimalWorkbook(file, """<row r="1"><c r="A1" s="1"><v>0</v></c></row>""", numFmtIds = listOf(0, 14), date1904 = true)
        assertEquals(listOf(listOf("1904-01-01")), reader.read(file).single().rows)
    }

    @Test fun `empty sheet has no rows`() {
        val file = tmp.newFile("empty.xlsx")
        writeMinimalWorkbook(file, "")
        assertEquals(emptyList<List<String>>(), reader.read(file).single().rows)
    }

    @Test fun `zip without a workbook is an IOException`() {
        val file = tmp.newFile("other.xlsx")
        writeZip(file, mapOf("hello.txt" to "hi"))
        assertThrows(IOException::class.java) { reader.read(file) }
    }

    @Test fun `a text file is not a workbook`() {
        val file = tmp.newFile("fake.xlsx").apply { writeText("name,age") }
        assertThrows(IOException::class.java) { reader.read(file) }
    }

    @Test fun `recognises date number formats`() {
        assertTrue(XlsxReader.isDateFormat(14, emptyMap()))
        assertFalse(XlsxReader.isDateFormat(0, emptyMap()))
        assertTrue(XlsxReader.isDateFormat(164, mapOf(164 to "yyyy\\-mm\\-dd")))
        assertFalse(XlsxReader.isDateFormat(165, mapOf(165 to "0.00")))
        assertFalse(XlsxReader.isDateFormat(166, mapOf(166 to "\"Day\" 0")))
        assertFalse(XlsxReader.isDateFormat(167, mapOf(167 to "[Red]0.00")))
    }

    @Test fun `converts serial numbers to ISO dates`() {
        assertEquals("2024-01-15", XlsxReader.serialToIso(45306.0, date1904 = false))
        assertEquals("2024-01-15T10:30:00", XlsxReader.serialToIso(45306.4375, date1904 = false))
        assertEquals("1904-01-01", XlsxReader.serialToIso(0.0, date1904 = true))
    }

    @Test fun `parses column letters`() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(25, XlsxReader.columnIndex("Z9"))
        assertEquals(26, XlsxReader.columnIndex("AA10"))
        assertEquals(701, XlsxReader.columnIndex("ZZ1"))
        assertNull(XlsxReader.columnIndex("12"))
    }
}
```

`app/src/test/java/dev/armen/fileconverter/engine/office/XlsxWriterTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class XlsxWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val writer = XlsxWriter()

    @Test fun `round trips through the reader`() {
        val file = tmp.newFile("out.xlsx")
        writer.write(
            listOf(
                listOf("id", "name", "amount"),
                listOf("007", "Ann, \"A\"", "12.50"),
                listOf("1234567890123456", "<tag> & more", "-3"),
                listOf("", "x"),
                listOf("a\u0001b"),
            ),
            file,
        )
        val sheet = XlsxReader(kxmlFactory).read(file).single()
        assertEquals("Sheet1", sheet.name)
        assertEquals(
            listOf(
                listOf("id", "name", "amount"),
                listOf("007", "Ann, \"A\"", "12.50"),
                listOf("1234567890123456", "<tag> & more", "-3"),
                listOf("", "x"),
                listOf("ab"),
            ),
            sheet.rows,
        )
    }

    @Test fun `keeps ids as text and real numbers as numbers`() {
        val file = tmp.newFile("types.xlsx")
        writer.write(listOf(listOf("007", "1234567890123456", "1e5", "-3", "12.50")), file)
        val xml = readZipEntry(file, "xl/worksheets/sheet1.xml")
        assertTrue(xml, xml.contains("""<c r="A1" t="inlineStr"><is><t xml:space="preserve">007</t></is></c>"""))
        assertTrue(xml, xml.contains("""<c r="B1" t="inlineStr">"""))
        assertTrue(xml, xml.contains("""<c r="C1" t="inlineStr">"""))
        assertTrue(xml, xml.contains("""<c r="D1"><v>-3</v></c>"""))
        assertTrue(xml, xml.contains("""<c r="E1"><v>12.50</v></c>"""))
    }

    @Test fun `writes every required package part`() {
        val file = tmp.newFile("parts.xlsx")
        writer.write(listOf(listOf("a")), file)
        ZipFile(file).use { zip ->
            listOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml",
            ).forEach { assertNotNull(it, zip.getEntry(it)) }
        }
    }

    @Test fun `column names`() =
        assertEquals(listOf("A", "Z", "AA", "ZZ", "AAA"), listOf(0, 25, 26, 701, 702).map { XlsxWriter.columnName(it) })

    @Test fun `sheet names are made valid`() {
        assertEquals("a_b_1_", XlsxWriter.safeSheetName("a/b[1]"))
        assertEquals(31, XlsxWriter.safeSheetName("x".repeat(40)).length)
        assertEquals("Sheet1", XlsxWriter.safeSheetName("  "))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.Xlsx*"`
Expected: FAIL — `Unresolved reference: XmlParserFactory`, `XlsxReader`, `XlsxWriter`.

- [ ] **Step 3: Implement the shared XML/zip helpers**

`app/src/main/java/dev/armen/fileconverter/engine/office/XmlSupport.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.xmlpull.v1.XmlPullParser

/** Creates a namespace-aware pull parser. Android uses [AndroidXml.factory]; JVM tests use kxml2. */
typealias XmlParserFactory = () -> XmlPullParser

/** Attribute by local name, ignoring its namespace. */
internal fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == localName) return getAttributeValue(i)
    return null
}

/** The `r:id` attribute, for both transitional and strict OOXML relationship namespaces. */
internal fun XmlPullParser.relationshipId(): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == "id" && getAttributeNamespace(i).orEmpty().endsWith("/relationships")) {
            return getAttributeValue(i)
        }
    }
    return null
}

/** Escapes text for element content and attributes, dropping characters XML 1.0 forbids. */
internal fun escapeXml(text: String): String = buildString(text.length) {
    for (ch in text) {
        when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
            ch < ' ' || ch == '￾' || ch == '￿' -> Unit
            else -> append(ch)
        }
    }
}
```

`app/src/main/java/dev/armen/fileconverter/engine/office/AndroidXml.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

object AndroidXml {
    val factory: XmlParserFactory = {
        Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
    }
}
```

`app/src/main/java/dev/armen/fileconverter/engine/office/OoxmlZip.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object OoxmlZip {
    private const val MAX_PART_BYTES = 256L * 1024 * 1024

    /** Reads the parts whose names match [wanted]. A file that is not a zip yields no parts. */
    fun read(file: File, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.trimStart('/')
                if (!entry.isDirectory && wanted(name)) parts[name] = readLimited(zip, name)
            }
        }
        return parts
    }

    private fun readLimited(input: InputStream, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_PART_BYTES) throw IOException("$name is larger than ${MAX_PART_BYTES / (1024 * 1024)} MB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

internal fun ZipOutputStream.putText(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}
```

- [ ] **Step 4: Implement the reader**

`app/src/main/java/dev/armen/fileconverter/engine/office/XlsxReader.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap

data class Sheet(val name: String, val rows: List<List<String>>)

/** Reads cell values (as displayed text) from every sheet of an XLSX workbook. */
class XlsxReader(private val newParser: XmlParserFactory) {

    private data class SheetRef(val name: String, val relId: String)
    private data class Workbook(val sheets: List<SheetRef>, val date1904: Boolean)

    fun read(file: File): List<Sheet> {
        val parts = OoxmlZip.read(file) { (it.startsWith("xl/") && it.endsWith(".xml")) || it.endsWith(".rels") }
        val workbookXml = parts["xl/workbook.xml"] ?: throw IOException("Not an XLSX file: xl/workbook.xml is missing")
        val workbook = parseWorkbook(workbookXml)
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let(::parseRelationships).orEmpty()
        val shared = parts["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val dateStyles = parts["xl/styles.xml"]?.let(::parseDateStyles).orEmpty()
        return workbook.sheets.mapNotNull { ref ->
            val target = rels[ref.relId] ?: return@mapNotNull null
            val bytes = parts[resolvePart(target)] ?: return@mapNotNull null
            Sheet(ref.name, parseSheet(bytes, shared, dateStyles, workbook.date1904))
        }
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        newParser().apply { setInput(ByteArrayInputStream(bytes), null) }

    private fun resolvePart(target: String): String =
        if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")

    private fun parseWorkbook(bytes: ByteArray): Workbook {
        val p = parser(bytes)
        val sheets = mutableListOf<SheetRef>()
        var date1904 = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
                "sheet" -> {
                    val id = p.relationshipId()
                    if (id != null) sheets += SheetRef(p.attr("name") ?: "Sheet${sheets.size + 1}", id)
                }
            }
        }
        return Workbook(sheets, date1904)
    }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val p = parser(bytes)
        val map = HashMap<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.attr("Id")
                val target = p.attr("Target")
                if (id != null && target != null) map[id] = target
            }
        }
        return map
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inText = false
        var phoneticDepth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> current.setLength(0)
                    "rPh" -> phoneticDepth++
                    "t" -> inText = phoneticDepth == 0
                }
                XmlPullParser.TEXT -> if (inText) current.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> strings += current.toString()
                    "rPh" -> phoneticDepth--
                    "t" -> inText = false
                }
            }
        }
        return strings
    }

    /** Indexes into cellXfs whose number format is a date. */
    private fun parseDateStyles(bytes: ByteArray): Set<Int> {
        val p = parser(bytes)
        val customFormats = HashMap<Int, String>()
        val xfFormats = mutableListOf<Int>()
        var inCellXfs = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "numFmt" -> {
                        val id = p.attr("numFmtId")?.toIntOrNull()
                        val code = p.attr("formatCode")
                        if (id != null && code != null) customFormats[id] = code
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) xfFormats += p.attr("numFmtId")?.toIntOrNull() ?: 0
                }
                XmlPullParser.END_TAG -> if (p.name == "cellXfs") inCellXfs = false
            }
        }
        return xfFormats.indices.filter { isDateFormat(xfFormats[it], customFormats) }.toSet()
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, dateStyles: Set<Int>, date1904: Boolean): List<List<String>> {
        val p = parser(bytes)
        val cells = TreeMap<Int, TreeMap<Int, String>>()
        var rowIndex = 0
        var nextRow = 0
        var nextCol = 0
        var col = 0
        var type: String? = null
        var style = 0
        var value: StringBuilder? = null
        val inline = StringBuilder()
        var inValue = false
        var inInlineText = false
        var phoneticDepth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        rowIndex = p.attr("r")?.toIntOrNull()?.minus(1) ?: nextRow
                        nextRow = rowIndex + 1
                        nextCol = 0
                    }
                    "c" -> {
                        col = p.attr("r")?.let(::columnIndex) ?: nextCol
                        nextCol = col + 1
                        type = p.attr("t")
                        style = p.attr("s")?.toIntOrNull() ?: 0
                        value = null
                        inline.setLength(0)
                    }
                    "v" -> {
                        inValue = true
                        value = StringBuilder()
                    }
                    "rPh" -> phoneticDepth++
                    "t" -> inInlineText = phoneticDepth == 0
                }
                XmlPullParser.TEXT -> {
                    if (inValue) value?.append(p.text)
                    if (inInlineText) inline.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "rPh" -> phoneticDepth--
                    "c" -> {
                        val text = cellText(type, value?.toString(), inline.toString(), style, shared, dateStyles, date1904)
                        if (text.isNotEmpty()) cells.getOrPut(rowIndex) { TreeMap() }[col] = text
                    }
                }
            }
        }
        if (cells.isEmpty()) return emptyList()
        return (0..cells.lastKey()).map { r ->
            val row = cells[r] ?: return@map emptyList()
            (0..row.lastKey()).map { c -> row[c].orEmpty() }
        }
    }

    private fun cellText(
        type: String?,
        v: String?,
        inline: String,
        style: Int,
        shared: List<String>,
        dateStyles: Set<Int>,
        date1904: Boolean,
    ): String = when (type) {
        "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
        "inlineStr" -> inline
        "b" -> when (v?.trim()) {
            "1" -> "TRUE"
            "0" -> "FALSE"
            else -> ""
        }
        "str", "e", "d" -> v.orEmpty()
        else -> {
            val raw = v?.trim().orEmpty()
            val number = raw.toDoubleOrNull()
            if (number != null && style in dateStyles) serialToIso(number, date1904) else raw
        }
    }

    internal companion object {
        private val BUILT_IN_DATE_FORMATS: Set<Int> = (14..22).toSet() + (45..47)
        private val QUOTED = Regex("\"[^\"]*\"")
        private val BRACKETED = Regex("\\[[^\\]]*\\]")
        private val ESCAPED = Regex("\\\\.")
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        fun isDateFormat(id: Int, custom: Map<Int, String>): Boolean {
            if (id in BUILT_IN_DATE_FORMATS) return true
            val code = custom[id] ?: return false
            val cleaned = code.replace(QUOTED, "").replace(BRACKETED, "").replace(ESCAPED, "").lowercase()
            return cleaned.any { it == 'y' || it == 'd' || it == 'h' }
        }

        fun serialToIso(serial: Double, date1904: Boolean): String {
            val epoch = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
            val totalSeconds = Math.round(serial * 86_400)
            val dateTime = epoch.plusSeconds(totalSeconds)
            return if (totalSeconds % 86_400 == 0L) dateTime.toLocalDate().toString() else dateTime.format(DATE_TIME)
        }

        fun columnIndex(ref: String): Int? {
            var index = 0
            var letters = 0
            for (ch in ref) {
                val upper = ch.uppercaseChar()
                if (upper !in 'A'..'Z') break
                index = index * 26 + (upper - 'A' + 1)
                letters++
            }
            return if (letters == 0) null else index - 1
        }
    }
}
```

- [ ] **Step 5: Implement the writer**

`app/src/main/java/dev/armen/fileconverter/engine/office/XlsxWriter.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import java.io.File
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes one sheet of text/number cells as a minimal, valid XLSX package. */
class XlsxWriter {

    fun write(rows: List<List<String>>, out: File, sheetName: String = "Sheet1") {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putText("[Content_Types].xml", CONTENT_TYPES)
            zip.putText("_rels/.rels", ROOT_RELS)
            zip.putText("xl/workbook.xml", workbookXml(safeSheetName(sheetName)))
            zip.putText("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.putText("xl/styles.xml", STYLES)
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val writer = zip.bufferedWriter(Charsets.UTF_8) // flushed, not closed: closing would close the zip
            writeSheet(rows, writer)
            writer.flush()
            zip.closeEntry()
        }
    }

    private fun writeSheet(rows: List<List<String>>, w: Writer) {
        w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="$MAIN_NS"><sheetData>""")
        rows.forEachIndexed { r, row ->
            if (row.all { it.isEmpty() }) return@forEachIndexed
            w.write("""<row r="${r + 1}">""")
            row.forEachIndexed { c, value ->
                if (value.isEmpty()) return@forEachIndexed
                val ref = columnName(c) + (r + 1)
                if (NUMBER.matches(value)) {
                    w.write("""<c r="$ref"><v>$value</v></c>""")
                } else {
                    w.write("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>""")
                }
            }
            w.write("</row>")
        }
        w.write("</sheetData></worksheet>")
    }

    private fun workbookXml(sheetName: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$MAIN_NS" xmlns:r="$REL_NS"><sheets><sheet name="${escapeXml(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    companion object {
        private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

        /** Plain decimals up to 15 significant integer digits; anything else (007, long IDs, 1e5) stays text. */
        private val NUMBER = Regex("-?(0|[1-9][0-9]{0,14})(\\.[0-9]+)?")

        private const val CONTENT_TYPES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

        private const val ROOT_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        private const val WORKBOOK_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$REL_NS/styles" Target="styles.xml"/></Relationships>"""

        private const val STYLES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="$MAIN_NS"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

        fun columnName(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.append('A' + rem)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        fun safeSheetName(name: String): String =
            name.replace(Regex("[\\[\\]:*?/\\\\]"), "_").take(31).ifBlank { "Sheet1" }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.Xlsx*"`
Expected: PASS (13 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine/office app/src/test/java/dev/armen/fileconverter/engine/office
git commit -m "feat: read and write XLSX without external libraries

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: DOCX reader and writer

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/DocxReader.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/DocxWriter.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/engine/office/DocxReaderTest.kt`, `DocxWriterTest.kt`

**Interfaces:**
- Consumes: `XmlParserFactory`, `OoxmlZip`, `putText`, `escapeXml` (Task 7); test helpers `kxmlFactory`, `writeZip` (Task 7).
- Produces: `class DocxReader(newParser: XmlParserFactory) { fun read(file: File): String }` (throws `IOException` for non-DOCX); `class DocxWriter { fun write(text: String, out: File); internal fun paragraph(line: String): String }`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/armen/fileconverter/engine/office/DocxReaderTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DocxReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val reader = DocxReader(kxmlFactory)

    private val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
<w:body>
<w:p><w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>world</w:t></w:r><w:r><w:delText>gone</w:delText></w:r></w:p>
<w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>r1c1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>r1c2</w:t></w:r></w:p><w:p><w:r><w:t>second</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>r2c1</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>
<w:p><w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><wps:txbx><w:txbxContent><w:p><w:r><w:t>Box</w:t></w:r></w:p></w:txbxContent></wps:txbx></w:drawing></mc:Choice><mc:Fallback><w:pict><w:txbxContent><w:p><w:r><w:t>Box</w:t></w:r></w:p></w:txbxContent></w:pict></mc:Fallback></mc:AlternateContent></w:r></w:p>
<w:p><w:r><w:t>End &amp; done</w:t></w:r></w:p>
<w:sectPr/>
</w:body></w:document>"""

    @Test fun `extracts paragraphs, tabs, breaks, tables and text boxes once`() {
        val file = tmp.newFile("doc.docx")
        writeZip(file, mapOf("word/document.xml" to document))
        assertEquals("Hello world\nA\tB\nC\nr1c1\tr1c2 second\nr2c1\t\nBox\nEnd & done", reader.read(file))
    }

    @Test fun `ignores text outside the wordprocessing namespace`() {
        val file = tmp.newFile("chart.docx")
        writeZip(
            file,
            mapOf(
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Keep</w:t></w:r><a:t xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">chart</a:t></w:p></w:body></w:document>""",
            ),
        )
        assertEquals("Keep", reader.read(file))
    }

    @Test fun `zip without a document part is an IOException`() {
        val file = tmp.newFile("other.docx")
        writeZip(file, mapOf("hello.txt" to "hi"))
        assertThrows(IOException::class.java) { reader.read(file) }
    }
}
```

`app/src/test/java/dev/armen/fileconverter/engine/office/DocxWriterTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class DocxWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val writer = DocxWriter()
    private val reader = DocxReader(kxmlFactory)

    @Test fun `round trips through the reader`() {
        val text = "Line 1\n\tIndented & <ok>\n\nLast"
        val file = tmp.newFile("out.docx")
        writer.write(text, file)
        assertEquals(text, reader.read(file))
    }

    @Test fun `normalises windows line endings`() {
        val file = tmp.newFile("crlf.docx")
        writer.write("a\r\nb", file)
        assertEquals("a\nb", reader.read(file))
    }

    @Test fun `paragraph markup`() {
        assertEquals(
            "<w:p><w:r><w:t xml:space=\"preserve\">a</w:t><w:tab/><w:t xml:space=\"preserve\">b &amp; c</w:t></w:r></w:p>",
            writer.paragraph("a\tb & c"),
        )
        assertEquals("<w:p/>", writer.paragraph(""))
    }

    @Test fun `writes every required package part`() {
        val file = tmp.newFile("parts.docx")
        writer.write("x", file)
        ZipFile(file).use { zip ->
            listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels", "word/styles.xml")
                .forEach { assertNotNull(it, zip.getEntry(it)) }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.Docx*"`
Expected: FAIL — `Unresolved reference: DocxReader`, `DocxWriter`.

- [ ] **Step 3: Implement the reader**

`app/src/main/java/dev/armen/fileconverter/engine/office/DocxReader.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Extracts plain text from word/document.xml: paragraphs become lines, w:tab becomes a tab,
 * table rows become lines of tab-separated cells, and mc:Fallback copies are skipped.
 */
class DocxReader(private val newParser: XmlParserFactory) {

    fun read(file: File): String {
        val xml = OoxmlZip.read(file) { it == "word/document.xml" }["word/document.xml"]
            ?: throw IOException("Not a DOCX file: word/document.xml is missing")
        return extractText(xml)
    }

    private fun extractText(xml: ByteArray): String {
        val p = newParser().apply { setInput(ByteArrayInputStream(xml), null) }
        val out = StringBuilder()
        var inText = false
        var fallbackDepth = 0
        var tabsDepth = 0
        var cellDepth = 0
        // One entry per open paragraph: did a nested paragraph (e.g. in a text box) end inside it?
        val paragraphs = ArrayDeque<Boolean>()

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when {
                    p.name == "Fallback" -> fallbackDepth++
                    fallbackDepth > 0 || !isWordNamespace(p.namespace) -> Unit
                    p.name == "p" -> paragraphs.addLast(false)
                    p.name == "t" -> inText = true
                    p.name == "tabs" -> tabsDepth++
                    p.name == "tab" && tabsDepth == 0 -> out.append('\t')
                    p.name == "br" || p.name == "cr" -> out.append('\n')
                    p.name == "tc" -> cellDepth++
                }
                XmlPullParser.TEXT -> if (inText && fallbackDepth == 0) out.append(p.text)
                XmlPullParser.END_TAG -> when {
                    p.name == "Fallback" -> fallbackDepth--
                    fallbackDepth > 0 || !isWordNamespace(p.namespace) -> Unit
                    p.name == "t" -> inText = false
                    p.name == "tabs" -> tabsDepth--
                    p.name == "p" -> {
                        val hadNested = paragraphs.removeLastOrNull() ?: false
                        if (paragraphs.isNotEmpty()) paragraphs[paragraphs.lastIndex] = true
                        when {
                            cellDepth > 0 -> out.append(' ')
                            hadNested && out.endsWith('\n') -> Unit
                            else -> out.append('\n')
                        }
                    }
                    p.name == "tc" -> {
                        while (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
                        out.append('\t')
                        cellDepth--
                    }
                    p.name == "tr" -> {
                        if (out.endsWith('\t')) out.setLength(out.length - 1)
                        out.append('\n')
                    }
                }
            }
        }
        return out.toString().trimEnd('\n')
    }

    private fun isWordNamespace(ns: String?): Boolean =
        ns != null && (ns.endsWith("wordprocessingml/2006/main") || ns.endsWith("wordprocessingml/main"))
}
```

- [ ] **Step 4: Implement the writer**

`app/src/main/java/dev/armen/fileconverter/engine/office/DocxWriter.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes plain text as a DOCX: one paragraph per line, tabs kept, Calibri 11 pt, A4 page. */
class DocxWriter {

    fun write(text: String, out: File) {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putText("[Content_Types].xml", CONTENT_TYPES)
            zip.putText("_rels/.rels", ROOT_RELS)
            zip.putText("word/_rels/document.xml.rels", DOCUMENT_RELS)
            zip.putText("word/styles.xml", STYLES)
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val writer = zip.bufferedWriter(Charsets.UTF_8) // flushed, not closed: closing would close the zip
            writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$W_NS"><w:body>""")
            lines.forEach { writer.write(paragraph(it)) }
            writer.write(SECTION)
            writer.write("</w:body></w:document>")
            writer.flush()
            zip.closeEntry()
        }
    }

    internal fun paragraph(line: String): String {
        if (line.isEmpty()) return "<w:p/>"
        val runs = line.split('\t').joinToString("<w:tab/>") { part ->
            if (part.isEmpty()) "" else """<w:t xml:space="preserve">${escapeXml(part)}</w:t>"""
        }
        return "<w:p><w:r>$runs</w:r></w:p>"
    }

    private companion object {
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

        const val CONTENT_TYPES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

        const val ROOT_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="word/document.xml"/></Relationships>"""

        const val DOCUMENT_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/styles" Target="styles.xml"/></Relationships>"""

        const val STYLES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W_NS"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""

        const val SECTION =
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.Docx*"`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine/office app/src/test/java/dev/armen/fileconverter/engine/office
git commit -m "feat: read and write DOCX text without external libraries

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Offline office converter

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/office/OfficeLiteConverter.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/engine/office/OfficeLiteConverterTest.kt`

**Interfaces:**
- Consumes: `Converter`, `ConversionTask`, `FileNames`, `TextDecoding`, `ErrorMapper`, `ConversionError` (Task 3); `CsvCodec` (Task 6); `XlsxReader`, `XlsxWriter`, `Sheet` (Task 7); `DocxReader`, `DocxWriter` (Task 8); test helpers `kxmlFactory`, `writeExcelFixture` (Task 7).
- Produces: `class OfficeLiteConverter(xlsxReader: XlsxReader, xlsxWriter: XlsxWriter, docxReader: DocxReader, docxWriter: DocxWriter, textToPdf: (String, File) -> Unit) : Converter`; companion `csvNames(base: String, sheetNames: List<String>): List<String>`. Pairs: XLSX→CSV (UTF-8 with BOM, one file per sheet), CSV→XLSX, DOCX→TXT (UTF-8), TXT→DOCX, DOCX→PDF (text only via `textToPdf`). Unreadable input → `ConversionError.UnreadableInput`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/armen/fileconverter/engine/office/OfficeLiteConverterTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.engine.ConversionTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfficeLiteConverterTest {
    @get:Rule val tmp = TemporaryFolder()
    private var pdfText: String? = null
    private val progress = mutableListOf<Float>()
    private val converter = OfficeLiteConverter(XlsxReader(kxmlFactory), XlsxWriter(), DocxReader(kxmlFactory), DocxWriter()) { text, out ->
        pdfText = text
        out.writeText("%PDF-fake")
    }

    private fun run(input: File, source: FileFormat, target: FileFormat): List<File> = runBlocking {
        converter.convert(ConversionTask(listOf(input), source, target, ConversionOptions(), tmp.newFolder(), "book")) { progress += it }
    }

    @Test fun `xlsx with two sheets gives one csv per sheet`() {
        val outputs = run(tmp.newFile("in.xlsx").also(::writeExcelFixture), FileFormat.XLSX, FileFormat.CSV)
        assertEquals(listOf("book-People.csv", "book-Q&A.csv"), outputs.map { it.name })
        val first = outputs[0].readText(Charsets.UTF_8)
        assertTrue(first.startsWith("﻿Name,Joined,Score\r\nAnn Lee,2024-01-15,98.5\r\n"))
        assertEquals("﻿x,7\r\n", outputs[1].readText(Charsets.UTF_8))
        assertEquals(1f, progress.last())
    }

    @Test fun `single sheet xlsx keeps the base name`() {
        val xlsx = tmp.newFile("in.xlsx").also { XlsxWriter().write(listOf(listOf("a", "b"), listOf("1", "2")), it) }
        val output = run(xlsx, FileFormat.XLSX, FileFormat.CSV).single()
        assertEquals("book.csv", output.name)
        assertEquals("﻿a,b\r\n1,2\r\n", output.readText(Charsets.UTF_8))
    }

    @Test fun `utf-16 semicolon csv becomes xlsx`() {
        val csv = tmp.newFile("in.csv").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "name;city\nAnn;Zürich\n".toByteArray(Charsets.UTF_16LE))
        }
        val output = run(csv, FileFormat.CSV, FileFormat.XLSX).single()
        assertEquals("book.xlsx", output.name)
        assertEquals(listOf(listOf("name", "city"), listOf("Ann", "Zürich")), XlsxReader(kxmlFactory).read(output).single().rows)
    }

    @Test fun `docx to txt`() {
        val docx = tmp.newFile("in.docx").also { DocxWriter().write("Hello\n\tWorld", it) }
        val output = run(docx, FileFormat.DOCX, FileFormat.TXT).single()
        assertEquals("book.txt", output.name)
        assertEquals("Hello\n\tWorld", output.readText(Charsets.UTF_8))
    }

    @Test fun `windows-1252 txt to docx`() {
        val txt = tmp.newFile("in.txt").apply { writeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())) }
        val output = run(txt, FileFormat.TXT, FileFormat.DOCX).single()
        assertEquals("café", DocxReader(kxmlFactory).read(output))
    }

    @Test fun `docx to pdf hands the text to the pdf writer`() {
        val docx = tmp.newFile("in.docx").also { DocxWriter().write("Report body", it) }
        val output = run(docx, FileFormat.DOCX, FileFormat.PDF).single()
        assertEquals("book.pdf", output.name)
        assertEquals("Report body", pdfText)
    }

    @Test fun `damaged docx is unreadable input`() {
        val bad = tmp.newFile("bad.docx").apply { writeText("not a zip") }
        assertThrows(ConversionError.UnreadableInput::class.java) { run(bad, FileFormat.DOCX, FileFormat.TXT) }
    }

    @Test fun `other pairs are unsupported`() {
        val pdf = tmp.newFile("in.pdf").apply { writeText("%PDF") }
        assertThrows(ConversionError.UnsupportedPair::class.java) { run(pdf, FileFormat.PDF, FileFormat.DOCX) }
    }

    @Test fun `csv names are unique per sheet`() {
        assertEquals(listOf("book.csv"), OfficeLiteConverter.csvNames("book", listOf("Only")))
        assertEquals(listOf("book-a_b.csv", "book-a_b-2.csv"), OfficeLiteConverter.csvNames("book", listOf("a/b", "a:b")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.OfficeLiteConverterTest"`
Expected: FAIL — `Unresolved reference: OfficeLiteConverter`.

- [ ] **Step 3: Implement the converter**

`app/src/main/java/dev/armen/fileconverter/engine/office/OfficeLiteConverter.kt`:
```kotlin
package dev.armen.fileconverter.engine.office

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.TextDecoding
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** The five offline office conversions. [textToPdf] renders plain text (LocalPdf's TextPdfWriter on device). */
class OfficeLiteConverter(
    private val xlsxReader: XlsxReader,
    private val xlsxWriter: XlsxWriter,
    private val docxReader: DocxReader,
    private val docxWriter: DocxWriter,
    private val textToPdf: (String, File) -> Unit,
) : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val input = task.inputs.single()
            val outputs = try {
                when (task.source to task.target) {
                    FileFormat.XLSX to FileFormat.CSV -> xlsxToCsv(input, task)
                    FileFormat.CSV to FileFormat.XLSX -> single(task) { out ->
                        xlsxWriter.write(CsvCodec.parse(TextDecoding.decode(input.readBytes())), out)
                    }
                    FileFormat.DOCX to FileFormat.TXT -> single(task) { out -> out.writeText(docxReader.read(input), Charsets.UTF_8) }
                    FileFormat.TXT to FileFormat.DOCX -> single(task) { out -> docxWriter.write(TextDecoding.decode(input.readBytes()), out) }
                    FileFormat.DOCX to FileFormat.PDF -> single(task) { out -> textToPdf(docxReader.read(input), out) }
                    else -> throw ConversionError.UnsupportedPair(task.source, task.target)
                }
            } catch (e: ConversionError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ErrorMapper.map(e)
                throw if (mapped is ConversionError.InsufficientStorage) mapped else ConversionError.UnreadableInput(task.source, e)
            }
            onProgress(1f)
            outputs
        }

    private inline fun single(task: ConversionTask, write: (File) -> Unit): List<File> {
        val out = File(task.outputDir, FileNames.withExtension(task.baseName, task.target))
        write(out)
        return listOf(out)
    }

    private fun xlsxToCsv(input: File, task: ConversionTask): List<File> {
        val sheets = xlsxReader.read(input).ifEmpty { listOf(Sheet("Sheet1", emptyList())) }
        val names = csvNames(task.baseName, sheets.map { it.name })
        return sheets.zip(names).map { (sheet, name) ->
            // BOM so Excel opens the UTF-8 file with the right encoding.
            File(task.outputDir, name).apply { writeText("﻿" + CsvCodec.write(sheet.rows), Charsets.UTF_8) }
        }
    }

    internal companion object {
        fun csvNames(base: String, sheetNames: List<String>): List<String> {
            if (sheetNames.size == 1) return listOf("$base.csv")
            val used = HashSet<String>()
            return sheetNames.map { sheet ->
                val stem = "$base-${FileNames.sanitize(sheet)}"
                var candidate = "$stem.csv"
                var n = 2
                while (!used.add(candidate.lowercase())) {
                    candidate = "$stem-$n.csv"
                    n++
                }
                candidate
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.office.*"`
Expected: PASS (all office tests, including the 9 new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine/office/OfficeLiteConverter.kt app/src/test/java/dev/armen/fileconverter/engine/office/OfficeLiteConverterTest.kt
git commit -m "feat: convert XLSX/CSV and DOCX/TXT/PDF offline

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Image and local PDF converters

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/BitmapLoader.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/ImageConverter.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/TextPdfWriter.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/LocalPdfConverter.kt`
- Test: `app/src/androidTest/java/dev/armen/fileconverter/engine/ImagePdfConverterTest.kt`

**Interfaces:**
- Consumes: `Converter`, `ConversionTask`, `ConversionError`, `FileNames`, `TextDecoding`, `FormatRegistry` (Tasks 2–3); `TestMedia` (Task 5).
- Produces:
  - `object BitmapLoader { const val MAX_DECODE_DIMENSION = 8192; fun load(file: File, maxDimension: Int?): Bitmap?; fun scaleDown(bitmap: Bitmap, limit: Int): Bitmap }`
  - `class ImageConverter : Converter` — JPG/PNG/WEBP output; transparent pixels become white in JPG.
  - `object TextPdfWriter { const val PAGE_WIDTH = 595; const val PAGE_HEIGHT = 842; fun write(text: String, out: File) }` — used by `OfficeLiteConverter` in Task 13.
  - `class LocalPdfConverter : Converter` — images→one PDF (A4, one image per page, centred), PDF→PNG/JPG (`<base>.png` for one page, `<base>-1.png`… for several), TXT→PDF.

- [ ] **Step 1: Write the failing instrumented test**

`app/src/androidTest/java/dev/armen/fileconverter/engine/ImagePdfConverterTest.kt`:
```kotlin
package dev.armen.fileconverter.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImagePdfConverterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File

    @Before fun setUp() { dir = File(context.cacheDir, "image-test-${System.nanoTime()}").apply { mkdirs() } }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun run(
        converter: Converter,
        inputs: List<File>,
        source: FileFormat,
        target: FileFormat,
        options: ConversionOptions = ConversionOptions(),
    ): List<File> = runBlocking {
        val out = File(dir, "out-${System.nanoTime()}").apply { mkdirs() }
        converter.convert(ConversionTask(inputs, source, target, options, out, "result")) {}
    }

    private fun ascii(file: File, from: Int, count: Int) =
        String(TestMedia.header(file, from + count), from, count, Charsets.US_ASCII)

    private fun pageCount(pdf: File): Int =
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

    private fun makePdf(pages: Int): File {
        val document = PdfDocument()
        repeat(pages) { i ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 300, i + 1).create())
            page.canvas.drawText("Page ${i + 1}", 20f, 40f, Paint())
            document.finishPage(page)
        }
        return File(dir, "doc.pdf").apply {
            outputStream().use { document.writeTo(it) }
            document.close()
        }
    }

    @Test fun transparentPngToJpgIsFlattenedOnWhite() {
        val jpg = run(ImageConverter(), listOf(TestMedia.png(dir, "a.png", width = 200, height = 100, alpha = true)), FileFormat.PNG, FileFormat.JPG).single()
        val head = TestMedia.header(jpg, 2)
        assertEquals(0xFF, head[0].toInt() and 0xFF)
        assertEquals(0xD8, head[1].toInt() and 0xFF)
        val decoded = BitmapFactory.decodeFile(jpg.path)
        assertEquals(200, decoded.width)
        val pixel = decoded.getPixel(150, 50) // right half was transparent
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test fun resizesToTheMaxDimension() {
        val png = run(ImageConverter(), listOf(TestMedia.png(dir, "big.png", width = 400, height = 200)), FileFormat.PNG, FileFormat.PNG, ConversionOptions(maxDimension = 100)).single()
        val decoded = BitmapFactory.decodeFile(png.path)
        assertEquals(100, decoded.width)
        assertEquals(50, decoded.height)
    }

    @Test fun pngToWebp() {
        val webp = run(ImageConverter(), listOf(TestMedia.png(dir, "a.png")), FileFormat.PNG, FileFormat.WEBP).single()
        assertEquals("RIFF", ascii(webp, 0, 4))
        assertEquals("WEBP", ascii(webp, 8, 4))
    }

    @Test fun corruptImageIsUnreadableInput() {
        val bad = File(dir, "bad.png").apply { writeText("not an image") }
        assertThrows(ConversionError.UnreadableInput::class.java) { run(ImageConverter(), listOf(bad), FileFormat.PNG, FileFormat.JPG) }
    }

    @Test fun imagesMergeIntoOnePdf() {
        val inputs = listOf(TestMedia.png(dir, "0-a.png"), TestMedia.png(dir, "1-b.png", width = 640, height = 200))
        val pdf = run(LocalPdfConverter(), inputs, FileFormat.PNG, FileFormat.PDF).single()
        assertEquals("result.pdf", pdf.name)
        assertEquals(2, pageCount(pdf))
    }

    @Test fun pdfPagesBecomeNumberedImages() {
        val outputs = run(LocalPdfConverter(), listOf(makePdf(3)), FileFormat.PDF, FileFormat.PNG)
        assertEquals(listOf("result-1.png", "result-2.png", "result-3.png"), outputs.map { it.name })
        outputs.forEach { assertEquals("PNG", ascii(it, 1, 3)) }
    }

    @Test fun singlePagePdfKeepsTheBaseName() =
        assertEquals("result.jpg", run(LocalPdfConverter(), listOf(makePdf(1)), FileFormat.PDF, FileFormat.JPG).single().name)

    @Test fun longTextSpansSeveralPdfPages() {
        val txt = File(dir, "long.txt").apply { writeText((1..300).joinToString("\n") { "Line $it\twith a tab" }) }
        val pdf = run(LocalPdfConverter(), listOf(txt), FileFormat.TXT, FileFormat.PDF).single()
        assertEquals("%PDF", ascii(pdf, 0, 4))
        assertTrue(pageCount(pdf) > 1)
    }

    @Test fun emptyTextGivesOnePage() {
        val txt = File(dir, "empty.txt").apply { writeText("") }
        assertEquals(1, pageCount(run(LocalPdfConverter(), listOf(txt), FileFormat.TXT, FileFormat.PDF).single()))
    }

    @Test fun damagedPdfIsUnreadableInput() {
        val bad = File(dir, "bad.pdf").apply { writeText("not a pdf") }
        assertThrows(ConversionError.UnreadableInput::class.java) { run(LocalPdfConverter(), listOf(bad), FileFormat.PDF, FileFormat.PNG) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.armen.fileconverter.engine.ImagePdfConverterTest`
Expected: FAIL — compilation errors `Unresolved reference: ImageConverter` / `LocalPdfConverter`.

- [ ] **Step 3: Implement the bitmap loader and image converter**

`app/src/main/java/dev/armen/fileconverter/engine/BitmapLoader.kt`:
```kotlin
package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes images upright (EXIF applied) and never larger than a limit, so memory stays bounded. */
object BitmapLoader {
    /** Hard cap so a 100-megapixel photo cannot exhaust memory. */
    const val MAX_DECODE_DIMENSION = 8192

    /** Returns null when the file is not a decodable image. */
    fun load(file: File, maxDimension: Int?): Bitmap? {
        val limit = minOf(maxDimension ?: MAX_DECODE_DIMENSION, MAX_DECODE_DIMENSION)
        return if (Build.VERSION.SDK_INT >= 28) decodeWithImageDecoder(file, limit) else decodeLegacy(file, limit)
    }

    fun scaleDown(bitmap: Bitmap, limit: Int): Bitmap {
        val scale = limit.toFloat() / max(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }

    @RequiresApi(28)
    private fun decodeWithImageDecoder(file: File, limit: Int): Bitmap? = try {
        // ImageDecoder applies EXIF orientation itself.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > limit) {
                val scale = limit.toFloat() / longest
                decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
            }
        }
    } catch (e: ImageDecoder.DecodeException) {
        null
    }

    private fun decodeLegacy(file: File, limit: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val upright = applyExifOrientation(file, decoded)
        return if (max(upright.width, upright.height) > limit) scaleDown(upright, limit) else upright
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
```

`app/src/main/java/dev/armen/fileconverter/engine/ImageConverter.kt`:
```kotlin
package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** JPG, PNG and WEBP output from any image Android can decode. */
class ImageConverter : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val input = task.inputs.single()
            val bitmap = BitmapLoader.load(input, task.options.maxDimension)
                ?: throw ConversionError.UnreadableInput(task.source)
            val output = File(task.outputDir, FileNames.withExtension(task.baseName, task.target))
            val quality = task.options.imageQuality.coerceIn(1, 100)
            val toWrite = if (task.target == FileFormat.JPG && bitmap.hasAlpha()) flattenOnWhite(bitmap) else bitmap
            val written = output.outputStream().buffered().use { toWrite.compress(compressFormat(task, quality), quality, it) }
            if (!written) throw ConversionError.EngineFailed("The ${task.target.label} encoder failed.")
            onProgress(1f)
            listOf(output)
        }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(task: ConversionTask, quality: Int): Bitmap.CompressFormat = when (task.target) {
        FileFormat.JPG -> Bitmap.CompressFormat.JPEG
        FileFormat.PNG -> Bitmap.CompressFormat.PNG
        FileFormat.WEBP -> when {
            Build.VERSION.SDK_INT >= 30 && quality >= 100 -> Bitmap.CompressFormat.WEBP_LOSSLESS
            Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        else -> throw ConversionError.UnsupportedPair(task.source, task.target)
    }
}
```

- [ ] **Step 4: Implement the text PDF writer and the local PDF converter**

`app/src/main/java/dev/armen/fileconverter/engine/TextPdfWriter.kt`:
```kotlin
package dev.armen.fileconverter.engine

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Renders plain text onto A4 pages (monospace 10 pt, word-wrapped). */
object TextPdfWriter {
    const val PAGE_WIDTH = 595 // A4 in PostScript points
    const val PAGE_HEIGHT = 842
    private const val MARGIN = 40

    fun write(text: String, out: File) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            color = Color.BLACK
        }
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val contentHeight = PAGE_HEIGHT - 2 * MARGIN
        val source = text.replace("\r\n", "\n").replace("\t", "    ")
        val layout = StaticLayout.Builder.obtain(source, 0, source.length, paint, contentWidth)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        val document = PdfDocument()
        try {
            var line = 0
            var pageNumber = 1
            do {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= contentHeight) end++
                if (end == line) end = line + 1 // a single line taller than a page still gets placed
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create())
                val canvas = page.canvas
                canvas.save()
                canvas.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
                canvas.clipRect(0, top, contentWidth, layout.getLineBottom(end - 1))
                layout.draw(canvas)
                canvas.restore()
                document.finishPage(page)
                line = end
            } while (line < layout.lineCount)
            out.outputStream().buffered().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
```

`app/src/main/java/dev/armen/fileconverter/engine/LocalPdfConverter.kt`:
```kotlin
package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.FormatRegistry
import dev.armen.fileconverter.domain.TextDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Images -> one PDF, PDF -> PNG/JPG pages, TXT -> PDF. All on device. */
class LocalPdfConverter : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            when {
                task.target == FileFormat.PDF && task.source.family == FormatFamily.IMAGE -> listOf(imagesToPdf(task, onProgress))
                task.source == FileFormat.PDF && (task.target == FileFormat.PNG || task.target == FileFormat.JPG) ->
                    pdfToImages(task, onProgress)
                task.source == FileFormat.TXT && task.target == FileFormat.PDF -> {
                    val out = File(task.outputDir, FileNames.withExtension(task.baseName, FileFormat.PDF))
                    TextPdfWriter.write(TextDecoding.decode(task.inputs.single().readBytes()), out)
                    onProgress(1f)
                    listOf(out)
                }
                else -> throw ConversionError.UnsupportedPair(task.source, task.target)
            }
        }

    private fun imagesToPdf(task: ConversionTask, onProgress: (Float) -> Unit): File {
        val out = File(task.outputDir, FileNames.withExtension(task.baseName, FileFormat.PDF))
        val document = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            task.inputs.forEachIndexed { index, file ->
                val bitmap = BitmapLoader.load(file, PDF_IMAGE_MAX_DIMENSION)
                    ?: throw ConversionError.UnreadableInput(FormatRegistry.detect(file.name, null))
                val landscape = bitmap.width > bitmap.height
                val pageWidth = if (landscape) TextPdfWriter.PAGE_HEIGHT else TextPdfWriter.PAGE_WIDTH
                val pageHeight = if (landscape) TextPdfWriter.PAGE_WIDTH else TextPdfWriter.PAGE_HEIGHT
                val scale = minOf(
                    (pageWidth - 2 * IMAGE_MARGIN).toFloat() / bitmap.width,
                    (pageHeight - 2 * IMAGE_MARGIN).toFloat() / bitmap.height,
                )
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (pageWidth - width) / 2f
                val top = (pageHeight - height) / 2f
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create())
                page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
                document.finishPage(page)
                onProgress((index + 1f) / task.inputs.size * 0.95f)
            }
            out.outputStream().buffered().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        onProgress(1f)
        return out
    }

    private fun pdfToImages(task: ConversionTask, onProgress: (Float) -> Unit): List<File> {
        val descriptor = ParcelFileDescriptor.open(task.inputs.single(), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            descriptor.close()
            throw ConversionError.EngineFailed("This PDF is password-protected.", e)
        } catch (e: IOException) {
            descriptor.close()
            throw ConversionError.UnreadableInput(FileFormat.PDF, e)
        }
        return renderer.use { pdf ->
            val count = pdf.pageCount
            val digits = count.toString().length
            (0 until count).map { index ->
                pdf.openPage(index).use { page ->
                    val scale = RENDER_DPI / 72f
                    var width = (page.width * scale).roundToInt()
                    var height = (page.height * scale).roundToInt()
                    val longest = max(width, height)
                    if (longest > MAX_RENDER_DIMENSION) {
                        val shrink = MAX_RENDER_DIMENSION.toFloat() / longest
                        width = max(1, (width * shrink).roundToInt())
                        height = max(1, (height * shrink).roundToInt())
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val name = if (count == 1) {
                        FileNames.withExtension(task.baseName, task.target)
                    } else {
                        "${task.baseName}-${(index + 1).toString().padStart(digits, '0')}.${task.target.extension}"
                    }
                    val out = File(task.outputDir, name)
                    val format = if (task.target == FileFormat.JPG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    out.outputStream().buffered().use { bitmap.compress(format, task.options.imageQuality.coerceIn(1, 100), it) }
                    bitmap.recycle()
                    onProgress((index + 1f) / count)
                    out
                }
            }
        }
    }

    private companion object {
        const val PDF_IMAGE_MAX_DIMENSION = 2000
        const val IMAGE_MARGIN = 24
        const val RENDER_DPI = 150
        const val MAX_RENDER_DIMENSION = 4096
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.armen.fileconverter.engine.ImagePdfConverterTest`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine app/src/androidTest/java/dev/armen/fileconverter/engine/ImagePdfConverterTest.kt
git commit -m "feat: convert images and PDFs on device

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: CloudConvert client and converter

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/engine/cloud/CloudConvertModels.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/cloud/ProgressRequestBody.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/cloud/CloudConvertClient.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/engine/cloud/CloudConvertConverter.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/engine/cloud/CloudConvertClientTest.kt`, `CloudConvertConverterTest.kt`

**Interfaces:**
- Consumes: `Converter`, `ConversionTask`, `ConversionError`, `ErrorMapper`, `FileNames` (Task 3).
- Produces:
  - DTOs `JobDto(id, status, tasks)`, `TaskDto(id, name, operation, status, code, message, result)`, `TaskResultDto(form, files)`, `UploadFormDto(url, parameters: Map<String, JsonElement>)`, `ExportFileDto(filename, url)`, `UserDto(username, email, credits)`
  - `class CloudConvertClient(http: OkHttpClient, apiKey: String, baseUrl: HttpUrl = PRODUCTION_URL)` with `suspend fun createJob(inputFormat: String, outputFormat: String): JobDto`, `getJob(id): JobDto`, `deleteJob(id)`, `me(): UserDto`, `upload(form, file, fileName, onProgress)`, `download(url, dest, onProgress)`; companion `PRODUCTION_URL`, `SANDBOX_URL`, `internal fun mapHttpError(code: Int, body: String?): ConversionError`
  - `class CloudConvertConverter(client: CloudConvertClient, pollIntervalMs: Long = 2_000, timeoutMs: Long = 30 * 60_000, maxInputBytes: Long = 1 GB) : Converter` ( deletes the remote job in `finally`)

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/armen/fileconverter/engine/cloud/CloudConvertClientTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConvertClientTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    @Test fun `me returns username and credits`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"id":1,"username":"armen","email":"x@y.z","credits":25}}"""))
        val user = CloudConvertClient(OkHttpClient(), "k", server.url("/v2/")).me()
        assertEquals("armen", user.username)
        assertEquals(25, user.credits)
        val request = server.takeRequest()
        assertEquals("/v2/users/me", request.path)
        assertEquals("Bearer k", request.getHeader("Authorization"))
    }

    @Test fun `maps http errors`() {
        assertTrue(CloudConvertClient.mapHttpError(401, null) is ConversionError.InvalidApiKey)
        assertTrue(CloudConvertClient.mapHttpError(403, null) is ConversionError.InvalidApiKey)
        assertTrue(CloudConvertClient.mapHttpError(402, null) is ConversionError.QuotaExceeded)
        assertTrue(CloudConvertClient.mapHttpError(429, null) is ConversionError.QuotaExceeded)
        assertEquals("CloudConvert: boom", CloudConvertClient.mapHttpError(500, """{"message":"boom"}""").details)
        assertEquals("CloudConvert: HTTP 404", CloudConvertClient.mapHttpError(404, "").details)
    }

    @Test fun `unreachable server is no network`() {
        val client = CloudConvertClient(OkHttpClient(), "k", "http://127.0.0.1:1/v2/".toHttpUrl())
        assertThrows(ConversionError.NoNetwork::class.java) { runBlocking { client.me() } }
    }
}
```

`app/src/test/java/dev/armen/fileconverter/engine/cloud/CloudConvertConverterTest.kt`:
```kotlin
package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.engine.ConversionTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class CloudConvertConverterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val deleted = CopyOnWriteArrayList<String>()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    private fun createJson() = """{"data":{"id":"job-1","status":"waiting","tasks":[
        {"id":"t1","name":"upload-file","operation":"import/upload","status":"waiting","result":{"form":{"url":"${server.url("/upload")}","parameters":{"expires":1700000000,"signature":"abc"}}}},
        {"id":"t2","name":"convert-file","operation":"convert","status":"waiting"},
        {"id":"t3","name":"export-file","operation":"export/url","status":"waiting"}]}}"""

    private val processingJson = """{"data":{"id":"job-1","status":"processing","tasks":[]}}"""

    private fun finishedJson() = """{"data":{"id":"job-1","status":"finished","tasks":[
        {"id":"t3","name":"export-file","operation":"export/url","status":"finished","result":{"files":[{"filename":"report.pdf","url":"${server.url("/files/report.pdf")}"}]}}]}}"""

    private val errorJson = """{"data":{"id":"job-1","status":"error","tasks":[
        {"id":"t2","name":"convert-file","operation":"convert","status":"error","code":"INVALID_CONVERSION_TYPE","message":"This conversion is not supported"}]}}"""

    private fun dispatcher(statuses: List<String>, defaultStatus: String = "finished", createCode: Int = 201) = object : Dispatcher() {
        private val queue = ConcurrentLinkedQueue(statuses)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                request.method == "POST" && path == "/v2/jobs" ->
                    if (createCode == 201) MockResponse().setResponseCode(201).setBody(createJson())
                    else MockResponse().setResponseCode(createCode).setBody("""{"message":"nope","code":"X"}""")
                request.method == "POST" && path == "/upload" -> MockResponse().setResponseCode(201)
                request.method == "GET" && path == "/v2/jobs/job-1" -> when (queue.poll() ?: defaultStatus) {
                    "processing" -> MockResponse().setBody(processingJson)
                    "error" -> MockResponse().setBody(errorJson)
                    else -> MockResponse().setBody(finishedJson())
                }
                request.method == "GET" && path == "/files/report.pdf" -> MockResponse().setBody("PDFDATA")
                request.method == "DELETE" && path == "/v2/jobs/job-1" -> {
                    deleted += path
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun converter() =
        CloudConvertConverter(CloudConvertClient(OkHttpClient(), "secret", server.url("/v2/")), pollIntervalMs = 5, timeoutMs = 5_000)

    private fun task(): ConversionTask {
        val input = tmp.newFile("report.docx").apply { writeText("DOCX") }
        return ConversionTask(listOf(input), FileFormat.DOCX, FileFormat.PDF, ConversionOptions(), tmp.newFolder("out"), "report")
    }

    @Test fun `uploads, polls, downloads and cleans up`() = runBlocking {
        server.dispatcher = dispatcher(listOf("processing", "finished"))
        val progress = mutableListOf<Float>()
        val outputs = converter().convert(task()) { progress += it }

        assertEquals(listOf("report.pdf"), outputs.map { it.name })
        assertEquals("PDFDATA", outputs.single().readText())
        val create = server.takeRequest()
        assertEquals("Bearer secret", create.getHeader("Authorization"))
        val body = create.body.readUtf8()
        assertTrue(body, body.contains("\"input_format\":\"docx\""))
        assertTrue(body, body.contains("\"output_format\":\"pdf\""))
        val upload = server.takeRequest()
        assertEquals("/upload", upload.path)
        assertNull(upload.getHeader("Authorization"))
        val uploadBody = upload.body.readUtf8()
        assertTrue(uploadBody.contains("name=\"signature\""))
        assertTrue(uploadBody.contains("1700000000"))
        assertTrue(uploadBody.contains("DOCX"))
        assertEquals(listOf("/v2/jobs/job-1"), deleted)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `rejected key`() {
        server.dispatcher = dispatcher(emptyList(), createCode = 401)
        assertThrows(ConversionError.InvalidApiKey::class.java) { runBlocking { converter().convert(task()) {} } }
    }

    @Test fun `out of credits`() {
        server.dispatcher = dispatcher(emptyList(), createCode = 402)
        assertThrows(ConversionError.QuotaExceeded::class.java) { runBlocking { converter().convert(task()) {} } }
    }

    @Test fun `failed job reports the task message and still cleans up`() {
        server.dispatcher = dispatcher(listOf("error"))
        val error = assertThrows(ConversionError.EngineFailed::class.java) { runBlocking { converter().convert(task()) {} } }
        assertEquals("CloudConvert: This conversion is not supported", error.details)
        assertEquals(listOf("/v2/jobs/job-1"), deleted)
    }

    @Test fun `cancelling deletes the remote job`() = runBlocking {
        server.dispatcher = dispatcher(emptyList(), defaultStatus = "processing")
        val conversion = task()
        val job = launch(Dispatchers.IO) { converter().convert(conversion) {} }
        withTimeout(5_000) { while (server.requestCount < 3) delay(10) } // create, upload, first poll
        job.cancelAndJoin()
        assertEquals(listOf("/v2/jobs/job-1"), deleted)
    }

    @Test fun `files over the size limit are refused before uploading`() {
        val small = CloudConvertConverter(CloudConvertClient(OkHttpClient(), "secret", server.url("/v2/")), maxInputBytes = 3)
        assertThrows(ConversionError.EngineFailed::class.java) { runBlocking { small.convert(task()) {} } } // input is 4 bytes
        assertEquals(0, server.requestCount)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.cloud.*"`
Expected: FAIL — `Unresolved reference: CloudConvertClient`, `CloudConvertConverter`.

- [ ] **Step 3: Implement models and the upload progress body**

`app/src/main/java/dev/armen/fileconverter/engine/cloud/CloudConvertModels.kt`:
```kotlin
package dev.armen.fileconverter.engine.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JobEnvelope(val data: JobDto)

@Serializable
data class JobDto(val id: String, val status: String, val tasks: List<TaskDto> = emptyList())

@Serializable
data class TaskDto(
    val id: String,
    val name: String? = null,
    val operation: String,
    val status: String,
    val code: String? = null,
    val message: String? = null,
    val result: TaskResultDto? = null,
)

@Serializable
data class TaskResultDto(val form: UploadFormDto? = null, val files: List<ExportFileDto>? = null)

/** Parameters must be sent back verbatim with the upload; values may be strings or numbers. */
@Serializable
data class UploadFormDto(val url: String, val parameters: Map<String, JsonElement> = emptyMap())

@Serializable
data class ExportFileDto(val filename: String, val url: String)

@Serializable
data class UserEnvelope(val data: UserDto)

@Serializable
data class UserDto(val username: String? = null, val email: String? = null, val credits: Int? = null)

@Serializable
data class ErrorDto(val message: String? = null, val code: String? = null)
```

`app/src/main/java/dev/armen/fileconverter/engine/cloud/ProgressRequestBody.kt`:
```kotlin
package dev.armen.fileconverter.engine.cloud

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** Wraps a request body and reports the fraction of bytes written. */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val counting = object : ForwardingSink(sink) {
            private var written = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }.buffer()
        delegate.writeTo(counting)
        counting.flush()
    }
}
```

- [ ] **Step 4: Implement the client**

`app/src/main/java/dev/armen/fileconverter/engine/cloud/CloudConvertClient.kt`:
```kotlin
package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ErrorMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin CloudConvert REST v2 client. Every call runs on Dispatchers.IO and is cancellable. */
class CloudConvertClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: HttpUrl = PRODUCTION_URL,
) {

    suspend fun createJob(inputFormat: String, outputFormat: String): JobDto {
        val body = buildJsonObject {
            putJsonObject("tasks") {
                putJsonObject("upload-file") { put("operation", "import/upload") }
                putJsonObject("convert-file") {
                    put("operation", "convert")
                    put("input", "upload-file")
                    put("input_format", inputFormat)
                    put("output_format", outputFormat)
                }
                putJsonObject("export-file") {
                    put("operation", "export/url")
                    put("input", "convert-file")
                }
            }
        }
        val request = authorized("jobs").post(body.toString().toRequestBody(JSON_MEDIA)).build()
        return json.decodeFromString(JobEnvelope.serializer(), execute(request)).data
    }

    suspend fun getJob(id: String): JobDto =
        json.decodeFromString(JobEnvelope.serializer(), execute(authorized("jobs/$id").get().build())).data

    suspend fun deleteJob(id: String) {
        execute(authorized("jobs/$id").delete().build())
    }

    suspend fun me(): UserDto =
        json.decodeFromString(UserEnvelope.serializer(), execute(authorized("users/me").get().build())).data

    /** Posts the file to the signed upload form. The form URL is pre-signed, so no Authorization header. */
    suspend fun upload(form: UploadFormDto, file: File, fileName: String, onProgress: (Float) -> Unit) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.parameters.forEach { (key, value) -> addFormDataPart(key, (value as? JsonPrimitive)?.content ?: value.toString()) }
            addFormDataPart("file", fileName, ProgressRequestBody(file.asRequestBody(OCTET_STREAM), onProgress))
        }.build()
        val request = Request.Builder().url(form.url).post(body).build()
        withContext(Dispatchers.IO) {
            call(request).use { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code, response.body?.string())
            }
        }
    }

    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            call(Request.Builder().url(url).get().build()).use { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code, response.body?.string())
                val body = response.body ?: throw ConversionError.EngineFailed("CloudConvert sent an empty file.")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        onProgress(1f)
    }

    private fun authorized(path: String): Request.Builder =
        Request.Builder()
            .url(baseUrl.resolve(path) ?: throw IllegalArgumentException("Bad path $path"))
            .header("Authorization", "Bearer $apiKey")

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        call(request).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(response.code, body)
            body
        }
    }

    private suspend fun call(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(ErrorMapper.map(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        val PRODUCTION_URL: HttpUrl = "https://api.cloudconvert.com/v2/".toHttpUrl()
        val SANDBOX_URL: HttpUrl = "https://api.sandbox.cloudconvert.com/v2/".toHttpUrl()
        private val JSON_MEDIA = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        internal fun mapHttpError(code: Int, body: String?): ConversionError {
            val message = body?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString(ErrorDto.serializer(), it).message }.getOrNull() }
                ?: "HTTP $code"
            return when (code) {
                401, 403 -> ConversionError.InvalidApiKey()
                402, 429 -> ConversionError.QuotaExceeded()
                else -> ConversionError.EngineFailed("CloudConvert: $message")
            }
        }
    }
}
```

- [ ] **Step 5: Implement the converter**

`app/src/main/java/dev/armen/fileconverter/engine/cloud/CloudConvertConverter.kt`:
```kotlin
package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Upload -> convert -> export job. Progress: upload 0–40 %, conversion 40–80 %, download 80–100 %.
 * The remote job is deleted afterwards on every outcome, including cancellation.
 */
class CloudConvertConverter(
    private val client: CloudConvertClient,
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 30 * 60_000,
    private val maxInputBytes: Long = MAX_INPUT_BYTES,
) : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> {
        val input = task.inputs.single()
        if (input.length() > maxInputBytes) throw ConversionError.EngineFailed("CloudConvert accepts files up to 1 GB.")
        val job = client.createJob(task.source.extension, task.target.extension)
        try {
            val form = job.tasks.firstOrNull { it.operation == "import/upload" }?.result?.form
                ?: throw ConversionError.EngineFailed("CloudConvert did not return an upload form.")
            // ASCII name: the output is renamed locally anyway.
            client.upload(form, input, "input.${task.source.extension}") { onProgress(it * 0.4f) }
            onProgress(0.4f)
            val finished = awaitFinished(job.id, onProgress)
            val files = finished.tasks.firstOrNull { it.operation == "export/url" }?.result?.files.orEmpty()
            if (files.isEmpty()) throw ConversionError.EngineFailed("CloudConvert returned no files.")
            val outputs = files.mapIndexed { index, file ->
                val name = if (files.size == 1) FileNames.withExtension(task.baseName, task.target) else FileNames.sanitize(file.filename)
                val dest = File(task.outputDir, name)
                client.download(file.url, dest) { p -> onProgress(0.8f + 0.2f * (index + p) / files.size) }
                dest
            }
            onProgress(1f)
            return outputs
        } finally {
            withContext(NonCancellable) { runCatching { client.deleteJob(job.id) } }
        }
    }

    private suspend fun awaitFinished(id: String, onProgress: (Float) -> Unit): JobDto {
        val maxPolls = (timeoutMs / pollIntervalMs).coerceAtLeast(1)
        for (attempt in 0 until maxPolls) {
            val job = client.getJob(id)
            when (job.status) {
                "finished" -> return job
                "error" -> throw taskError(job)
            }
            onProgress(0.4f + 0.4f * (1f - 1f / (attempt + 2))) // creeps toward 80 %
            delay(pollIntervalMs)
        }
        throw ConversionError.EngineFailed("CloudConvert took too long.")
    }

    private fun taskError(job: JobDto): ConversionError {
        val failed = job.tasks.firstOrNull { it.status == "error" }
        return if (failed?.code?.contains("CREDIT", ignoreCase = true) == true) {
            ConversionError.QuotaExceeded()
        } else {
            ConversionError.EngineFailed("CloudConvert: ${failed?.message ?: "conversion failed"}")
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 1L shl 30
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.engine.cloud.*"`
Expected: PASS (9 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/engine/cloud app/src/test/java/dev/armen/fileconverter/engine/cloud
git commit -m "feat: convert documents through CloudConvert

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Settings, encrypted API key, history and file I/O

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/data/SecretCipher.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/data/SettingsRepository.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/data/history/HistoryEntry.kt`, `HistoryDao.kt`, `HistoryDatabase.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/data/InputResolver.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/data/OutputStore.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/data/SelectionHolder.kt`
- Test: `app/src/test/java/dev/armen/fileconverter/data/SettingsRepositoryTest.kt`
- Test: `app/src/androidTest/java/dev/armen/fileconverter/data/DataLayerTest.kt`

**Interfaces:**
- Consumes: `FileFormat`, `FormatRegistry`, `ConversionOptions`, `VideoQuality`, `ErrorMapper` (Tasks 2–3).
- Produces:
  - `interface SecretCipher { fun encrypt(plain: String): String; fun decrypt(encoded: String): String }`; `class KeystoreSecretCipher(alias: String = "cloudconvert_api_key") : SecretCipher`
  - `data class AppSettings(hasApiKey = false, sandbox = false, defaultImageQuality = 90, defaultAudioBitrateKbps = 192, defaultVideoQuality: VideoQuality? = null)`
  - `class SettingsRepository(store: DataStore<Preferences>, cipher: SecretCipher)` with `val settings: Flow<AppSettings>`, `suspend fun apiKey(): String?`, `setApiKey(key: String?)`, `setSandbox(Boolean)`, `setDefaultImageQuality(Int)`, `setDefaultAudioBitrate(Int)`, `setDefaultVideoQuality(VideoQuality?)`, `defaultOptions(): ConversionOptions`
  - `@Entity data class HistoryEntry(id: Long = 0, createdAt: Long, sourceName: String, sourceFormat: FileFormat, targetFormat: FileFormat, outputUri: String, outputName: String, outputSizeBytes: Long)`; `HistoryDao { observeRecent(limit: Int): Flow<List<HistoryEntry>>; suspend insert(entry): Long; suspend delete(id: Long); suspend clear() }`; `HistoryDatabase.create(context)`
  - `data class InputInfo(uri: Uri, displayName: String, sizeBytes: Long?, mimeType: String?) { val format: FileFormat? }`; `class InputResolver(resolver: ContentResolver) { fun resolve(uri: Uri): InputInfo; fun copyToFile(uri: Uri, dest: File) }`
  - `class OutputStore(context: Context) { data class Saved(uri: Uri, displayName: String, sizeBytes: Long); fun save(file: File, mimeType: String, subfolder: String? = null): Saved }` — throws `ConversionError`
  - `class SelectionHolder { val inputs: StateFlow<List<InputInfo>>; fun set(inputs: List<InputInfo>) }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/armen/fileconverter/data/SettingsRepositoryTest.kt`:
```kotlin
package dev.armen.fileconverter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(encoded: String): String {
            check(encoded.startsWith("enc:")) { "cannot decrypt" }
            return encoded.removePrefix("enc:").reversed()
        }
    }

    @Before fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "settings.preferences_pb") })
        repo = SettingsRepository(store, cipher)
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `starts with defaults`() = runBlocking {
        assertEquals(AppSettings(), repo.settings.first())
        assertNull(repo.apiKey())
    }

    @Test fun `api key is trimmed and stored encrypted`() = runBlocking {
        repo.setApiKey("  key-123 ")
        assertEquals("enc:321-yek", store.data.first()[stringPreferencesKey("api_key_enc")])
        assertEquals("key-123", repo.apiKey())
        assertTrue(repo.settings.first().hasApiKey)
    }

    @Test fun `blank key removes it`() = runBlocking {
        repo.setApiKey("k")
        repo.setApiKey("   ")
        assertNull(repo.apiKey())
        assertFalse(repo.settings.first().hasApiKey)
    }

    @Test fun `undecryptable key reads as missing`() = runBlocking {
        store.edit { it[stringPreferencesKey("api_key_enc")] = "garbage" }
        assertNull(repo.apiKey())
    }

    @Test fun `defaults feed conversion options`() = runBlocking {
        repo.setDefaultImageQuality(150)
        repo.setDefaultAudioBitrate(320)
        repo.setDefaultVideoQuality(VideoQuality.SMALL)
        assertEquals(
            ConversionOptions(imageQuality = 100, audioBitrateKbps = 320, videoQuality = VideoQuality.SMALL),
            repo.defaultOptions(),
        )
        repo.setDefaultVideoQuality(null)
        assertNull(repo.settings.first().defaultVideoQuality)
    }
}
```

`app/src/androidTest/java/dev/armen/fileconverter/data/DataLayerTest.kt`:
```kotlin
package dev.armen.fileconverter.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.armen.fileconverter.data.history.HistoryDatabase
import dev.armen.fileconverter.data.history.HistoryEntry
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataLayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun keystoreCipherRoundTrips() {
        val cipher = KeystoreSecretCipher("test_alias")
        val encrypted = cipher.encrypt("secret-key")
        assertNotEquals("secret-key", encrypted)
        assertEquals("secret-key", cipher.decrypt(encrypted))
    }

    @Test fun outputStoreSavesToDownloadsAndInputResolverReadsIt() {
        val file = File(context.cacheDir, "store-test.txt").apply { writeText("hello") }
        val saved = OutputStore(context).save(file, "text/plain", subfolder = "test-${System.nanoTime()}")
        try {
            val resolver = InputResolver(context.contentResolver)
            val info = resolver.resolve(saved.uri)
            assertEquals(saved.displayName, info.displayName)
            assertEquals(5L, info.sizeBytes)
            assertEquals(FileFormat.TXT, info.format)
            val copy = File(context.cacheDir, "copy.txt")
            resolver.copyToFile(saved.uri, copy)
            assertEquals("hello", copy.readText())
        } finally {
            context.contentResolver.delete(saved.uri, null, null)
        }
    }

    @Test fun historyDaoReturnsNewestFirst() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val dao = db.historyDao()
        listOf(1L, 3L, 2L).forEach { t ->
            dao.insert(
                HistoryEntry(
                    createdAt = t, sourceName = "f$t.docx", sourceFormat = FileFormat.DOCX, targetFormat = FileFormat.PDF,
                    outputUri = "content://x/$t", outputName = "f$t.pdf", outputSizeBytes = t,
                ),
            )
        }
        assertEquals(listOf(3L, 2L), dao.observeRecent(2).first().map { it.createdAt })
        dao.delete(dao.observeRecent(1).first().single().id)
        assertEquals(listOf(2L, 1L), dao.observeRecent(10).first().map { it.createdAt })
        dao.clear()
        assertTrue(dao.observeRecent(10).first().isEmpty())
        db.close()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.data.*"`
Expected: FAIL — `Unresolved reference: SettingsRepository`, `SecretCipher`, `AppSettings`.

- [ ] **Step 3: Implement the cipher and settings**

`app/src/main/java/dev/armen/fileconverter/data/SecretCipher.kt`:
```kotlin
package dev.armen.fileconverter.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output: Base64(iv || ciphertext). */
class KeystoreSecretCipher(private val alias: String = "cloudconvert_api_key") : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
```

`app/src/main/java/dev/armen/fileconverter/data/SettingsRepository.kt`:
```kotlin
package dev.armen.fileconverter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.VideoQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val hasApiKey: Boolean = false,
    val sandbox: Boolean = false,
    val defaultImageQuality: Int = 90,
    val defaultAudioBitrateKbps: Int = 192,
    val defaultVideoQuality: VideoQuality? = null,
)

class SettingsRepository(private val store: DataStore<Preferences>, private val cipher: SecretCipher) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key_enc")
        val SANDBOX = booleanPreferencesKey("sandbox")
        val IMAGE_QUALITY = intPreferencesKey("image_quality")
        val AUDIO_BITRATE = intPreferencesKey("audio_bitrate")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            hasApiKey = p[Keys.API_KEY] != null,
            sandbox = p[Keys.SANDBOX] ?: false,
            defaultImageQuality = p[Keys.IMAGE_QUALITY] ?: 90,
            defaultAudioBitrateKbps = p[Keys.AUDIO_BITRATE] ?: 192,
            defaultVideoQuality = p[Keys.VIDEO_QUALITY]?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } },
        )
    }

    /** The decrypted key; null when none is saved or it can no longer be decrypted (e.g. Keystore reset). */
    suspend fun apiKey(): String? =
        store.data.first()[Keys.API_KEY]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    suspend fun setApiKey(key: String?) {
        val trimmed = key?.trim()
        store.edit { p ->
            if (trimmed.isNullOrEmpty()) {
                p.remove(Keys.API_KEY)
            } else {
                p[Keys.API_KEY] = cipher.encrypt(trimmed)
            }
        }
    }

    suspend fun setSandbox(enabled: Boolean) {
        store.edit { it[Keys.SANDBOX] = enabled }
    }

    suspend fun setDefaultImageQuality(quality: Int) {
        store.edit { it[Keys.IMAGE_QUALITY] = quality.coerceIn(1, 100) }
    }

    suspend fun setDefaultAudioBitrate(kbps: Int) {
        store.edit { it[Keys.AUDIO_BITRATE] = kbps }
    }

    suspend fun setDefaultVideoQuality(quality: VideoQuality?) {
        store.edit { p ->
            if (quality == null) {
                p.remove(Keys.VIDEO_QUALITY)
            } else {
                p[Keys.VIDEO_QUALITY] = quality.name
            }
        }
    }

    suspend fun defaultOptions(): ConversionOptions = settings.first().let {
        ConversionOptions(
            imageQuality = it.defaultImageQuality,
            audioBitrateKbps = it.defaultAudioBitrateKbps,
            videoQuality = it.defaultVideoQuality,
        )
    }
}
```

- [ ] **Step 4: Implement history, input/output and selection**

`app/src/main/java/dev/armen/fileconverter/data/history/HistoryEntry.kt`:
```kotlin
package dev.armen.fileconverter.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.armen.fileconverter.domain.FileFormat

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val sourceName: String,
    val sourceFormat: FileFormat,
    val targetFormat: FileFormat,
    val outputUri: String,
    val outputName: String,
    val outputSizeBytes: Long,
)
```

`app/src/main/java/dev/armen/fileconverter/data/history/HistoryDao.kt`:
```kotlin
package dev.armen.fileconverter.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntry>>

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}
```

`app/src/main/java/dev/armen/fileconverter/data/history/HistoryDatabase.kt`:
```kotlin
package dev.armen.fileconverter.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()
    }
}
```

`app/src/main/java/dev/armen/fileconverter/data/InputResolver.kt`:
```kotlin
package dev.armen.fileconverter.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatRegistry
import java.io.File
import java.io.IOException

data class InputInfo(val uri: Uri, val displayName: String, val sizeBytes: Long?, val mimeType: String?) {
    val format: FileFormat? get() = FormatRegistry.detect(displayName, mimeType)
}

class InputResolver(private val resolver: ContentResolver) {

    fun resolve(uri: Uri): InputInfo {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = if (c.isNull(0)) null else c.getString(0)
                    size = if (c.isNull(1)) null else c.getLong(1)
                }
            }
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        return InputInfo(uri, displayName, size, runCatching { resolver.getType(uri) }.getOrNull())
    }

    fun copyToFile(uri: Uri, dest: File) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { source -> dest.outputStream().use { source.copyTo(it) } }
    }
}
```

`app/src/main/java/dev/armen/fileconverter/data/OutputStore.kt`:
```kotlin
package dev.armen.fileconverter.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dev.armen.fileconverter.domain.ErrorMapper
import java.io.File
import java.io.IOException

/** Saves results to Download/FileConverter[/subfolder]. Throws ConversionError. */
class OutputStore(private val context: Context) {

    data class Saved(val uri: Uri, val displayName: String, val sizeBytes: Long)

    fun save(file: File, mimeType: String, subfolder: String? = null): Saved = try {
        if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(file, mimeType, subfolder) else saveToAppDownloads(file, subfolder)
    } catch (e: IOException) {
        throw ErrorMapper.map(e)
    }

    @RequiresApi(29)
    private fun saveToMediaStore(file: File, mimeType: String, subfolder: String?): Saved {
        val resolver = context.contentResolver
        val relativePath = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, ROOT_FOLDER, subfolder).joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create ${file.name} in Downloads")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not write ${file.name}")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: file.name
        return Saved(uri, name, file.length())
    }

    private fun saveToAppDownloads(file: File, subfolder: String?): Saved {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("External storage is not available")
        val dir = File(root, listOfNotNull(ROOT_FOLDER, subfolder).joinToString("/")).apply { mkdirs() }
        val dest = uniqueFile(dir, file.name)
        file.copyTo(dest)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
        return Saved(uri, dest.name, dest.length())
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return candidate
    }

    companion object {
        const val ROOT_FOLDER = "FileConverter"
    }
}
```

`app/src/main/java/dev/armen/fileconverter/data/SelectionHolder.kt`:
```kotlin
package dev.armen.fileconverter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The files the user picked or shared, shown on the Convert screen. */
class SelectionHolder {
    private val _inputs = MutableStateFlow<List<InputInfo>>(emptyList())
    val inputs: StateFlow<List<InputInfo>> = _inputs.asStateFlow()

    fun set(inputs: List<InputInfo>) {
        _inputs.value = inputs
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.data.*"`
Expected: PASS (5 tests).
Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.armen.fileconverter.data.DataLayerTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/data app/src/test/java/dev/armen/fileconverter/data app/src/androidTest/java/dev/armen/fileconverter/data
git commit -m "feat: add settings with encrypted API key, history and file I/O

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Conversion queue (runner, worker, scheduler) and app wiring

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/work/ConversionRunner.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/work/ConversionLock.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/work/ConversionNotifications.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/work/ConversionWorker.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/work/ConversionScheduler.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/di/AppContainer.kt`
- Modify: `app/src/main/java/dev/armen/fileconverter/FileConverterApp.kt` (replace whole file)
- Test: `app/src/test/java/dev/armen/fileconverter/work/ConversionRunnerTest.kt`
- Test: `app/src/androidTest/java/dev/armen/fileconverter/work/WorkerEndToEndTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–12 (`FormatRegistry`, `ConversionRequest`, `ConversionError`, `ErrorMapper`, `FileNames`, all converters, `TextPdfWriter`, `AndroidXml`, `SettingsRepository`, `KeystoreSecretCipher`, `HistoryDatabase`, `InputResolver`, `OutputStore`, `SelectionHolder`, `CloudConvertClient`).
- Produces:
  - `enum class CloudStatus { AVAILABLE, NO_KEY, OFFLINE }`; `data class RunResult(val route: Route, val outputs: List<File>)`
  - `class ConversionRunner(sdkInt: Int, cloudStatus: suspend () -> CloudStatus, converterFor: suspend (Engine) -> Converter) { suspend fun run(request: ConversionRequest, inputs: List<File>, outputDir: File, onProgress: (Float) -> Unit): RunResult }` — throws `ConversionError` (never wraps `CancellationException`)
  - `object ConversionLock { val mutex: Mutex }`
  - `class ConversionWorker` with companion keys `KEY_REQUEST_FILE, KEY_PROGRESS, KEY_STAGE, KEY_OUTPUT_URIS, KEY_OUTPUT_NAMES, KEY_OUTPUT_SIZES, KEY_OUTPUT_TOTAL, KEY_BASIC, KEY_ERROR_KIND, KEY_ERROR_MESSAGE, KEY_ERROR_DETAILS`, stages `STAGE_WAITING, STAGE_CONVERTING, STAGE_SAVING`. Always returns `Result.success`; failures are in `KEY_ERROR_*`.
  - `class ConversionScheduler(workManager: WorkManager) { fun enqueue(batchId: String, batchDir: File, requests: List<ConversionRequest>): String /* batch tag */; fun observe(tag: String): Flow<List<WorkInfo>>; fun cancel(tag: String) }`; companion `TAG_ALL = "conversion"`, `LABEL_PREFIX = "label:"`, `INDEX_PREFIX = "index:"`, `batchTag(id)`, `batchId(tag)`
  - `class ConversionNotifications(context) { fun ensureChannel(); fun foregroundInfo(workId: UUID, title: String, stage: String, progress: Float): ForegroundInfo }`
  - `class AppContainer(context)` exposing `settings`, `history: HistoryDao`, `inputResolver`, `outputStore`, `notifications`, `selection`, `scheduler`, `queueDir(): File`, `cloudClient(apiKey: String, sandbox: Boolean): CloudConvertClient`, `runner(): ConversionRunner`, `pruneStaleQueue(now: Long = System.currentTimeMillis())`
  - `FileConverterApp.container: AppContainer`

- [ ] **Step 1: Write the failing runner test**

`app/src/test/java/dev/armen/fileconverter/work/ConversionRunnerTest.kt`:
```kotlin
package dev.armen.fileconverter.work

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionInput
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.domain.Fidelity
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.Route
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class ConversionRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val usedEngines = mutableListOf<Engine>()
    private var lastTask: ConversionTask? = null

    private val writesOneFile = object : Converter {
        override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> {
            lastTask = task
            onProgress(1f)
            return listOf(File(task.outputDir, "${task.baseName}.${task.target.extension}").apply { writeText("x") })
        }
    }

    private fun converterThatThrows(error: Throwable) = object : Converter {
        override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> = throw error
    }

    private val producesNothing = object : Converter {
        override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> = emptyList()
    }

    private fun runner(status: CloudStatus, converter: Converter = writesOneFile) =
        ConversionRunner(34, cloudStatus = { status }, converterFor = { engine -> usedEngines += engine; converter })

    private fun request(source: FileFormat, target: FileFormat, name: String = "My file.docx", inputs: Int = 1) =
        ConversionRequest(List(inputs) { ConversionInput("/in/$it", name) }, source, target)

    private fun run(runner: ConversionRunner, request: ConversionRequest): RunResult = runBlocking {
        runner.run(request, request.inputs.map { File(it.path) }, tmp.newFolder()) {}
    }

    @Test fun `docx to pdf without a key falls back to basic offline`() {
        val result = run(runner(CloudStatus.NO_KEY), request(FileFormat.DOCX, FileFormat.PDF))
        assertEquals(Route(Engine.OFFICE_LITE, Fidelity.BASIC), result.route)
        assertEquals(listOf(Engine.OFFICE_LITE), usedEngines)
    }

    @Test fun `docx to pdf with cloud available uses cloud`() =
        assertEquals(Engine.CLOUD, run(runner(CloudStatus.AVAILABLE), request(FileFormat.DOCX, FileFormat.PDF)).route.engine)

    @Test fun `cloud-only pair without a key asks for one`() {
        assertThrows(ConversionError.MissingApiKey::class.java) { run(runner(CloudStatus.NO_KEY), request(FileFormat.DOCX, FileFormat.ODT)) }
    }

    @Test fun `cloud-only pair while offline reports no network`() {
        assertThrows(ConversionError.NoNetwork::class.java) { run(runner(CloudStatus.OFFLINE), request(FileFormat.DOCX, FileFormat.ODT)) }
    }

    @Test fun `impossible pair is unsupported`() {
        assertThrows(ConversionError.UnsupportedPair::class.java) { run(runner(CloudStatus.AVAILABLE), request(FileFormat.MP3, FileFormat.PDF)) }
    }

    @Test fun `output base name comes from the sanitised display name`() {
        run(runner(CloudStatus.AVAILABLE), request(FileFormat.MP4, FileFormat.MP3, name = "Holiday: day 1.mp4"))
        assertEquals("Holiday_ day 1", lastTask!!.baseName)
    }

    @Test fun `only images to pdf may have several inputs`() {
        assertThrows(ConversionError.EngineFailed::class.java) {
            run(runner(CloudStatus.AVAILABLE), request(FileFormat.MP4, FileFormat.MP3, inputs = 2))
        }
        assertEquals(Engine.LOCAL_PDF, run(runner(CloudStatus.AVAILABLE), request(FileFormat.JPG, FileFormat.PDF, name = "a.jpg", inputs = 3)).route.engine)
    }

    @Test fun `engine exceptions are mapped`() {
        assertThrows(ConversionError.InsufficientStorage::class.java) {
            run(runner(CloudStatus.AVAILABLE, converterThatThrows(IOException("write failed: ENOSPC"))), request(FileFormat.WAV, FileFormat.MP3))
        }
    }

    @Test fun `empty output is an engine failure`() {
        assertThrows(ConversionError.EngineFailed::class.java) {
            run(runner(CloudStatus.AVAILABLE, producesNothing), request(FileFormat.WAV, FileFormat.MP3))
        }
    }

    @Test fun `cancellation is passed through untouched`() {
        assertThrows(CancellationException::class.java) {
            run(runner(CloudStatus.AVAILABLE, converterThatThrows(CancellationException("stop"))), request(FileFormat.WAV, FileFormat.MP3))
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.work.ConversionRunnerTest"`
Expected: FAIL — `Unresolved reference: ConversionRunner`, `CloudStatus`.

- [ ] **Step 3: Implement the runner**

`app/src/main/java/dev/armen/fileconverter/work/ConversionRunner.kt`:
```kotlin
package dev.armen.fileconverter.work

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.FormatRegistry
import dev.armen.fileconverter.domain.Route
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

enum class CloudStatus { AVAILABLE, NO_KEY, OFFLINE }

data class RunResult(val route: Route, val outputs: List<File>)

/** Picks the engine for a request and runs it. Android-free, so it is unit-tested on the JVM. */
class ConversionRunner(
    private val sdkInt: Int,
    private val cloudStatus: suspend () -> CloudStatus,
    private val converterFor: suspend (Engine) -> Converter,
) {

    suspend fun run(request: ConversionRequest, inputs: List<File>, outputDir: File, onProgress: (Float) -> Unit): RunResult {
        try {
            val status = cloudStatus()
            val route = FormatRegistry.chooseRoute(request.source, request.target, sdkInt, cloudAvailable = status == CloudStatus.AVAILABLE)
                ?: throw noRouteError(request, status)
            if (inputs.size > 1 && route.engine != Engine.LOCAL_PDF) {
                throw ConversionError.EngineFailed("Only images can be combined into one file.")
            }
            outputDir.mkdirs()
            val task = ConversionTask(
                inputs = inputs,
                source = request.source,
                target = request.target,
                options = request.options,
                outputDir = outputDir,
                baseName = FileNames.baseName(request.inputs.first().displayName),
            )
            val outputs = converterFor(route.engine).convert(task, onProgress)
            if (outputs.isEmpty()) throw ConversionError.EngineFailed("The converter produced no files.")
            return RunResult(route, outputs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ErrorMapper.map(e)
        }
    }

    private fun noRouteError(request: ConversionRequest, status: CloudStatus): ConversionError = when {
        FormatRegistry.routesFor(request.source, request.target, sdkInt).isEmpty() ->
            ConversionError.UnsupportedPair(request.source, request.target)
        status == CloudStatus.NO_KEY -> ConversionError.MissingApiKey()
        else -> ConversionError.NoNetwork()
    }
}
```

- [ ] **Step 4: Run the runner test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.work.ConversionRunnerTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Write the failing end-to-end worker test**

`app/src/androidTest/java/dev/armen/fileconverter/work/WorkerEndToEndTest.kt`:
```kotlin
package dev.armen.fileconverter.work

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import dev.armen.fileconverter.FileConverterApp
import dev.armen.fileconverter.domain.ConversionInput
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkerEndToEndTest {
    private val app = ApplicationProvider.getApplicationContext<FileConverterApp>()
    private val container get() = app.container

    private fun newBatch(): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        return id to File(container.queueDir(), id).apply { mkdirs() }
    }

    private fun awaitFinished(tag: String): WorkInfo = runBlocking {
        withTimeout(60_000) {
            container.scheduler.observe(tag).first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }.single()
        }
    }

    @Test fun txtToPdfIsSavedToDownloads() {
        val (id, dir) = newBatch()
        val input = File(dir, "0-notes.txt").apply { writeText("hello\nworld") }
        val request = ConversionRequest(listOf(ConversionInput(input.path, "notes.txt")), FileFormat.TXT, FileFormat.PDF)
        val info = awaitFinished(container.scheduler.enqueue(id, dir, listOf(request)))

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertNull(info.outputData.getString(ConversionWorker.KEY_ERROR_KIND))
        val uri = Uri.parse(info.outputData.getStringArray(ConversionWorker.KEY_OUTPUT_URIS)!!.single())
        try {
            val head = ByteArray(4)
            app.contentResolver.openInputStream(uri)!!.use { it.read(head) }
            assertEquals("%PDF", String(head, Charsets.US_ASCII))
            assertFalse("input should be deleted after conversion", input.exists())
        } finally {
            app.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun unsupportedPairReportsAnErrorWithoutFailingTheQueue() {
        val (id, dir) = newBatch()
        val input = File(dir, "0-a.mp3").apply { writeText("x") }
        val request = ConversionRequest(listOf(ConversionInput(input.path, "a.mp3")), FileFormat.MP3, FileFormat.PDF)
        val info = awaitFinished(container.scheduler.enqueue(id, dir, listOf(request)))

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(ErrorKind.UNSUPPORTED_PAIR.name, info.outputData.getString(ConversionWorker.KEY_ERROR_KIND))
    }

    @Test fun largeMergeRequestCanBeEnqueued() {
        val (id, dir) = newBatch()
        val inputs = List(150) { ConversionInput(File(dir, "$it-" + "x".repeat(120) + ".jpg").path, "photo-$it.jpg") }
        val tag = container.scheduler.enqueue(id, dir, listOf(ConversionRequest(inputs, FileFormat.JPG, FileFormat.PDF)))
        assertTrue(tag.startsWith("batch:"))
        container.scheduler.cancel(tag)
        dir.deleteRecursively()
    }
}
```

- [ ] **Step 6: Implement the lock, notifications, worker and scheduler**

`app/src/main/java/dev/armen/fileconverter/work/ConversionLock.kt`:
```kotlin
package dev.armen.fileconverter.work

import kotlinx.coroutines.sync.Mutex

/** Serialises conversions process-wide: FFmpeg and large PDFs should never run in parallel on a phone. */
object ConversionLock {
    val mutex = Mutex()
}
```

`app/src/main/java/dev/armen/fileconverter/work/ConversionNotifications.kt`:
```kotlin
package dev.armen.fileconverter.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import dev.armen.fileconverter.R
import java.util.UUID
import kotlin.math.roundToInt

class ConversionNotifications(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Conversions", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun foregroundInfo(workId: UUID, title: String, stage: String, progress: Float): ForegroundInfo {
        val percent = (progress * 100).roundToInt()
        val text = when (stage) {
            ConversionWorker.STAGE_WAITING -> "Waiting…"
            ConversionWorker.STAGE_SAVING -> "Saving…"
            else -> "$percent%"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, stage != ConversionWorker.STAGE_CONVERTING || progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()
        val id = workId.hashCode()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "conversions"
    }
}
```

`app/src/main/java/dev/armen/fileconverter/work/ConversionWorker.kt`:
```kotlin
package dev.armen.fileconverter.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.armen.fileconverter.FileConverterApp
import dev.armen.fileconverter.data.OutputStore
import dev.armen.fileconverter.data.history.HistoryEntry
import dev.armen.fileconverter.di.AppContainer
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.Fidelity
import dev.armen.fileconverter.domain.FileNames
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one ConversionRequest. Always returns Result.success: failures travel in KEY_ERROR_* so the
 * UI shows them and other queued items are unaffected. Inputs are deleted after success or failure,
 * but kept on cancellation/stop so WorkManager can retry a stopped worker.
 */
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FileConverterApp).container
        val requestFile = inputData.getString(KEY_REQUEST_FILE)?.let(::File)
        val request = requestFile?.let { file -> runCatching { ConversionRequest.fromJson(file.readText()) }.getOrNull() }
            ?: return Result.success(errorData(ConversionError.EngineFailed("The conversion request is missing.")))
        val title = if (request.inputs.size > 1) {
            "${request.inputs.size} files → ${request.target.label}"
        } else {
            "${request.inputs.first().displayName} → ${request.target.label}"
        }
        val workDir = File(applicationContext.cacheDir, "conversions/$id")
        report(container, title, STAGE_WAITING, 0f)

        return ConversionLock.mutex.withLock {
            coroutineScope {
                val progress = MutableStateFlow(0f)
                val reporter = launch {
                    progress.collect { p ->
                        report(container, title, STAGE_CONVERTING, p)
                        delay(PROGRESS_INTERVAL_MS) // StateFlow conflates updates in the meantime
                    }
                }
                try {
                    val result = container.runner().run(request, request.inputs.map { File(it.path) }, workDir) { progress.value = it }
                    reporter.cancel()
                    report(container, title, STAGE_SAVING, 1f)
                    val subfolder = if (result.outputs.size > 1) FileNames.baseName(request.inputs.first().displayName) else null
                    val saved = result.outputs.map { container.outputStore.save(it, request.target.mimeType, subfolder) }
                    saved.forEach { recordHistory(container, request, it) }
                    deleteInputs(request, requestFile)
                    Result.success(successData(saved, basic = result.route.fidelity == Fidelity.BASIC))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    deleteInputs(request, requestFile)
                    Result.success(errorData(ErrorMapper.map(e)))
                } finally {
                    reporter.cancel()
                    workDir.deleteRecursively()
                }
            }
        }
    }

    private suspend fun report(container: AppContainer, title: String, stage: String, progress: Float) {
        setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STAGE to stage))
        try {
            setForeground(container.notifications.foregroundInfo(id, title, stage, progress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Foreground start can be refused (e.g. Android 12+ while in background); keep working without it.
        }
    }

    private suspend fun recordHistory(container: AppContainer, request: ConversionRequest, saved: OutputStore.Saved) {
        try {
            container.history.insert(
                HistoryEntry(
                    createdAt = System.currentTimeMillis(),
                    sourceName = request.inputs.first().displayName,
                    sourceFormat = request.source,
                    targetFormat = request.target,
                    outputUri = saved.uri.toString(),
                    outputName = saved.displayName,
                    outputSizeBytes = saved.sizeBytes,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // History is a convenience; the file is already saved.
        }
    }

    private fun deleteInputs(request: ConversionRequest, requestFile: File?) {
        request.inputs.forEach { File(it.path).delete() }
        requestFile?.delete()
        requestFile?.parentFile?.let { dir -> if (dir.list().isNullOrEmpty()) dir.delete() }
    }

    companion object {
        const val KEY_REQUEST_FILE = "request_file"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_OUTPUT_URIS = "output_uris"
        const val KEY_OUTPUT_NAMES = "output_names"
        const val KEY_OUTPUT_SIZES = "output_sizes"
        const val KEY_OUTPUT_TOTAL = "output_total"
        const val KEY_BASIC = "basic"
        const val KEY_ERROR_KIND = "error_kind"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_ERROR_DETAILS = "error_details"
        const val STAGE_WAITING = "waiting"
        const val STAGE_CONVERTING = "converting"
        const val STAGE_SAVING = "saving"
        private const val PROGRESS_INTERVAL_MS = 300L

        /** WorkManager Data is limited to 10 KB, so only the first outputs are listed; the rest are counted. */
        private const val MAX_LISTED_OUTPUTS = 40

        fun successData(saved: List<OutputStore.Saved>, basic: Boolean): Data {
            val listed = saved.take(MAX_LISTED_OUTPUTS)
            return Data.Builder()
                .putStringArray(KEY_OUTPUT_URIS, listed.map { it.uri.toString() }.toTypedArray())
                .putStringArray(KEY_OUTPUT_NAMES, listed.map { it.displayName }.toTypedArray())
                .putLongArray(KEY_OUTPUT_SIZES, listed.map { it.sizeBytes }.toLongArray())
                .putInt(KEY_OUTPUT_TOTAL, saved.size)
                .putBoolean(KEY_BASIC, basic)
                .build()
        }

        fun errorData(error: ConversionError): Data = Data.Builder()
            .putString(KEY_ERROR_KIND, error.kind.name)
            .putString(KEY_ERROR_MESSAGE, error.message)
            .apply { error.details?.let { putString(KEY_ERROR_DETAILS, it.take(2_000)) } }
            .build()
    }
}
```

`app/src/main/java/dev/armen/fileconverter/work/ConversionScheduler.kt`:
```kotlin
package dev.armen.fileconverter.work

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.armen.fileconverter.domain.ConversionRequest
import kotlinx.coroutines.flow.Flow
import java.io.File

class ConversionScheduler(private val workManager: WorkManager) {

    /**
     * Writes each request to [batchDir] (WorkManager input data is limited to 10 KB) and enqueues one
     * worker per request. Returns the batch tag used to observe or cancel the batch.
     */
    fun enqueue(batchId: String, batchDir: File, requests: List<ConversionRequest>): String {
        val tag = batchTag(batchId)
        batchDir.mkdirs()
        requests.forEachIndexed { index, request ->
            val file = File(batchDir, "request-$index.json").apply { writeText(request.toJson()) }
            val label = if (request.inputs.size > 1) {
                "${request.inputs.size} images → ${request.target.label}"
            } else {
                "${request.inputs.first().displayName} → ${request.target.label}"
            }
            val work = OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(workDataOf(ConversionWorker.KEY_REQUEST_FILE to file.absolutePath))
                .addTag(TAG_ALL)
                .addTag(tag)
                .addTag(LABEL_PREFIX + label)
                .addTag(INDEX_PREFIX + index)
                .build()
            workManager.enqueue(work)
        }
        return tag
    }

    fun observe(tag: String): Flow<List<WorkInfo>> = workManager.getWorkInfosByTagFlow(tag)

    fun cancel(tag: String) {
        workManager.cancelAllWorkByTag(tag)
    }

    companion object {
        const val TAG_ALL = "conversion"
        const val LABEL_PREFIX = "label:"
        const val INDEX_PREFIX = "index:"
        private const val BATCH_PREFIX = "batch:"

        fun batchTag(batchId: String) = BATCH_PREFIX + batchId

        fun batchId(tag: String) = tag.removePrefix(BATCH_PREFIX)
    }
}
```

- [ ] **Step 7: Implement the container and application**

`app/src/main/java/dev/armen/fileconverter/di/AppContainer.kt`:
```kotlin
package dev.armen.fileconverter.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import dev.armen.fileconverter.data.InputResolver
import dev.armen.fileconverter.data.KeystoreSecretCipher
import dev.armen.fileconverter.data.OutputStore
import dev.armen.fileconverter.data.SelectionHolder
import dev.armen.fileconverter.data.SettingsRepository
import dev.armen.fileconverter.data.history.HistoryDao
import dev.armen.fileconverter.data.history.HistoryDatabase
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.engine.Converter
import dev.armen.fileconverter.engine.FfmpegConverter
import dev.armen.fileconverter.engine.ImageConverter
import dev.armen.fileconverter.engine.LocalPdfConverter
import dev.armen.fileconverter.engine.TextPdfWriter
import dev.armen.fileconverter.engine.cloud.CloudConvertClient
import dev.armen.fileconverter.engine.cloud.CloudConvertConverter
import dev.armen.fileconverter.engine.office.AndroidXml
import dev.armen.fileconverter.engine.office.DocxReader
import dev.armen.fileconverter.engine.office.DocxWriter
import dev.armen.fileconverter.engine.office.OfficeLiteConverter
import dev.armen.fileconverter.engine.office.XlsxReader
import dev.armen.fileconverter.engine.office.XlsxWriter
import dev.armen.fileconverter.work.CloudStatus
import dev.armen.fileconverter.work.ConversionNotifications
import dev.armen.fileconverter.work.ConversionRunner
import dev.armen.fileconverter.work.ConversionScheduler
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency injection: one instance per process, owned by FileConverterApp. */
class AppContainer(private val context: Context) {

    val settings = SettingsRepository(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") }),
        KeystoreSecretCipher(),
    )
    private val database by lazy { HistoryDatabase.create(context) }
    val history: HistoryDao get() = database.historyDao()
    val inputResolver = InputResolver(context.contentResolver)
    val outputStore = OutputStore(context)
    val notifications = ConversionNotifications(context)
    val selection = SelectionHolder()
    val scheduler by lazy { ConversionScheduler(WorkManager.getInstance(context)) }
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /** Inputs copied from the user's Uris wait here until their worker runs. */
    fun queueDir(): File = File(context.filesDir, "queue")

    fun cloudClient(apiKey: String, sandbox: Boolean): CloudConvertClient =
        CloudConvertClient(http, apiKey, if (sandbox) CloudConvertClient.SANDBOX_URL else CloudConvertClient.PRODUCTION_URL)

    fun runner(): ConversionRunner =
        ConversionRunner(Build.VERSION.SDK_INT, cloudStatus = ::cloudStatus, converterFor = ::converterFor)

    /** Deletes queued inputs older than two days (left behind by cancelled or killed work). */
    fun pruneStaleQueue(now: Long = System.currentTimeMillis()) {
        queueDir().listFiles()
            ?.filter { now - it.lastModified() > STALE_QUEUE_MS }
            ?.forEach { it.deleteRecursively() }
    }

    private suspend fun cloudStatus(): CloudStatus = when {
        settings.apiKey() == null -> CloudStatus.NO_KEY
        !isOnline() -> CloudStatus.OFFLINE
        else -> CloudStatus.AVAILABLE
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun converterFor(engine: Engine): Converter = when (engine) {
        Engine.IMAGE_NATIVE -> ImageConverter()
        Engine.FFMPEG -> FfmpegConverter()
        Engine.LOCAL_PDF -> LocalPdfConverter()
        Engine.OFFICE_LITE -> OfficeLiteConverter(
            XlsxReader(AndroidXml.factory), XlsxWriter(), DocxReader(AndroidXml.factory), DocxWriter(), TextPdfWriter::write,
        )
        Engine.CLOUD -> {
            val key = settings.apiKey() ?: throw ConversionError.MissingApiKey()
            CloudConvertConverter(cloudClient(key, settings.settings.first().sandbox))
        }
    }

    private companion object {
        const val STALE_QUEUE_MS = 48L * 60 * 60 * 1000
    }
}
```

`app/src/main/java/dev/armen/fileconverter/FileConverterApp.kt` (replace the whole file):
```kotlin
package dev.armen.fileconverter

import android.app.Application
import dev.armen.fileconverter.di.AppContainer
import kotlin.concurrent.thread

class FileConverterApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannel()
        thread(name = "queue-prune") { container.pruneStaleQueue() }
    }
}
```

- [ ] **Step 8: Run all tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (all JVM tests so far).
Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.armen.fileconverter.work.WorkerEndToEndTest`
Expected: PASS (3 tests).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/work app/src/main/java/dev/armen/fileconverter/di app/src/main/java/dev/armen/fileconverter/FileConverterApp.kt app/src/test/java/dev/armen/fileconverter/work app/src/androidTest/java/dev/armen/fileconverter/work
git commit -m "feat: queue conversions in a foreground WorkManager worker

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Navigation, Home and Settings screens, share-into-app

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/ui/Routes.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/AppNavHost.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/common/Formatting.kt`, `FileActions.kt`, `ChoiceChips.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/home/HomeViewModel.kt`, `HomeScreen.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt`
- Modify: `app/src/main/java/dev/armen/fileconverter/MainActivity.kt` (replace whole file)
- Test: `app/src/test/java/dev/armen/fileconverter/ui/common/FormattingTest.kt`

**Interfaces:**
- Consumes: `AppContainer` (Task 13), `HistoryDao`/`HistoryEntry`, `SettingsRepository`/`AppSettings`, `InputResolver`, `SelectionHolder` (Task 12), `CloudConvertClient` (Task 11), `ConversionOptions`, `VideoQuality`, `ErrorMapper`, `FormatRegistry` (Tasks 2–3).
- Produces:
  - `object Routes { HOME, SETTINGS, CONVERT, PROGRESS = "progress/{batch}", RESULT = "result/{batch}", BATCH_ARG = "batch"; fun progress(tag); fun result(tag) }`
  - `@Composable fun AppNavHost(container: AppContainer, shareSignal: Int)` — with placeholder destinations for CONVERT, PROGRESS and RESULT that Tasks 15–16 replace.
  - `object Formatting { fun bytes(size: Long?): String }`
  - `object FileActions { fun open(context, uri, mimeType); fun share(context, uris, mimeType); fun openWebPage(context, url); fun mimeTypeFor(name: String): String }`
  - `@Composable fun <T> ChoiceChips(label: String, choices: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit)`
  - `HomeViewModel(history: HistoryDao)`, `HomeScreen(viewModel, onFilesPicked: (List<Uri>) -> Unit, onOpenSettings: () -> Unit)`
  - `SettingsViewModel(settings, clientFactory: (String, Boolean) -> CloudConvertClient, history)`, `SettingsScreen(viewModel, onBack)`
  - `MainActivity` handles `ACTION_SEND` / `ACTION_SEND_MULTIPLE` by filling `container.selection` and bumping `shareSignal`, which navigates to Convert.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/armen/fileconverter/ui/common/FormattingTest.kt`:
```kotlin
package dev.armen.fileconverter.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test fun `formats byte sizes`() {
        assertEquals("", Formatting.bytes(null))
        assertEquals("0 B", Formatting.bytes(0))
        assertEquals("1023 B", Formatting.bytes(1023))
        assertEquals("1.5 KB", Formatting.bytes(1536))
        assertEquals("1.0 MB", Formatting.bytes(1_048_576))
        assertEquals("5.0 GB", Formatting.bytes(5_368_709_120))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.ui.common.FormattingTest"`
Expected: FAIL — `Unresolved reference: Formatting`.

- [ ] **Step 3: Implement the shared UI helpers**

`app/src/main/java/dev/armen/fileconverter/ui/common/Formatting.kt`:
```kotlin
package dev.armen.fileconverter.ui.common

import java.util.Locale

object Formatting {
    fun bytes(size: Long?): String {
        if (size == null || size < 0) return ""
        if (size < 1024) return "$size B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
```

`app/src/main/java/dev/armen/fileconverter/ui/common/FileActions.kt`:
```kotlin
package dev.armen.fileconverter.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.armen.fileconverter.domain.FormatRegistry

object FileActions {

    fun open(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(context, "No app on this phone can open this file.")
        } catch (e: SecurityException) {
            toast(context, "This file is no longer available.")
        }
    }

    fun share(context: Context, uris: List<Uri>, mimeType: String) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mimeType
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(context, "No app can receive this file.")
        }
    }

    fun openWebPage(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(context, "No browser found.")
        }
    }

    fun mimeTypeFor(name: String): String = FormatRegistry.detect(name, null)?.mimeType ?: "*/*"

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
```

`app/src/main/java/dev/armen/fileconverter/ui/common/ChoiceChips.kt`:
```kotlin
package dev.armen.fileconverter.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A label plus single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    label: String,
    choices: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.ui.common.FormattingTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Implement Home**

`app/src/main/java/dev/armen/fileconverter/ui/home/HomeViewModel.kt`:
```kotlin
package dev.armen.fileconverter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.data.history.HistoryDao
import dev.armen.fileconverter.data.history.HistoryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val history: HistoryDao) : ViewModel() {
    val recent: StateFlow<List<HistoryEntry>> =
        history.observeRecent(50).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(id: Long) {
        viewModelScope.launch { history.delete(id) }
    }
}
```

`app/src/main/java/dev/armen/fileconverter/ui/home/HomeScreen.kt`:
```kotlin
package dev.armen.fileconverter.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.data.history.HistoryEntry
import dev.armen.fileconverter.ui.common.FileActions
import dev.armen.fileconverter.ui.common.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onFilesPicked: (List<Uri>) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val history by viewModel.recent.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onFilesPicked(uris)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Converter") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick files", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Images, audio, video and documents. You can also share files to this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Text("Recent conversions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            }
            if (history.isEmpty()) {
                item { Text("Nothing converted yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(history, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    onOpen = { FileActions.open(context, entry.outputUri.toUri(), entry.targetFormat.mimeType) },
                    onShare = { FileActions.share(context, listOf(entry.outputUri.toUri()), entry.targetFormat.mimeType) },
                    onDelete = { viewModel.remove(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val details = listOf(
        "${entry.sourceFormat.label} → ${entry.targetFormat.label}",
        Formatting.bytes(entry.outputSizeBytes),
        DateUtils.getRelativeTimeSpanString(entry.createdAt).toString(),
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    ListItem(
        headlineContent = { Text(entry.outputName) },
        supportingContent = { Text(details) },
        trailingContent = {
            Row {
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share ${entry.outputName}") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove from history") }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
```

- [ ] **Step 6: Implement Settings**

`app/src/main/java/dev/armen/fileconverter/ui/settings/SettingsViewModel.kt`:
```kotlin
package dev.armen.fileconverter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.data.AppSettings
import dev.armen.fileconverter.data.SettingsRepository
import dev.armen.fileconverter.data.history.HistoryDao
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.VideoQuality
import dev.armen.fileconverter.engine.cloud.CloudConvertClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Testing : KeyTestState
    data class Ok(val username: String?, val credits: Int?) : KeyTestState
    data class Failed(val message: String) : KeyTestState
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val clientFactory: (apiKey: String, sandbox: Boolean) -> CloudConvertClient,
    private val history: HistoryDao,
) : ViewModel() {

    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _keyTest = MutableStateFlow<KeyTestState>(KeyTestState.Idle)
    val keyTest: StateFlow<KeyTestState> = _keyTest.asStateFlow()

    fun saveKey(key: String) = launch {
        settings.setApiKey(key)
        _keyTest.value = KeyTestState.Idle
    }

    fun removeKey() = launch {
        settings.setApiKey(null)
        _keyTest.value = KeyTestState.Idle
    }

    fun testKey() = launch {
        _keyTest.value = KeyTestState.Testing
        val key = settings.apiKey()
        _keyTest.value = if (key == null) {
            KeyTestState.Failed("Save a key first.")
        } else {
            try {
                val user = clientFactory(key, settings.settings.first().sandbox).me()
                KeyTestState.Ok(user.username, user.credits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyTestState.Failed(ErrorMapper.map(e).message ?: "The test failed.")
            }
        }
    }

    fun setSandbox(enabled: Boolean) = launch { settings.setSandbox(enabled) }

    fun setImageQuality(quality: Int) = launch { settings.setDefaultImageQuality(quality) }

    fun setAudioBitrate(kbps: Int) = launch { settings.setDefaultAudioBitrate(kbps) }

    fun setVideoQuality(quality: VideoQuality?) = launch { settings.setDefaultVideoQuality(quality) }

    fun clearHistory() = launch { history.clear() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
```

`app/src/main/java/dev/armen/fileconverter/ui/settings/SettingsScreen.kt`:
```kotlin
package dev.armen.fileconverter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.VideoQuality
import dev.armen.fileconverter.ui.common.ChoiceChips
import dev.armen.fileconverter.ui.common.FileActions
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val keyTest by viewModel.keyTest.collectAsStateWithLifecycle()
    var keyInput by rememberSaveable { mutableStateOf("") }
    var quality by remember(settings.defaultImageQuality) { mutableFloatStateOf(settings.defaultImageQuality.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("CloudConvert", style = MaterialTheme.typography.titleMedium)
            Text(
                "Office and e-book documents are converted by CloudConvert. Your key is stored encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (settings.hasApiKey) "Replace API key" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (settings.hasApiKey) Text("A key is saved.", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Save") }
                OutlinedButton(onClick = viewModel::testKey, enabled = settings.hasApiKey && keyTest != KeyTestState.Testing) { Text("Test") }
                TextButton(onClick = viewModel::removeKey, enabled = settings.hasApiKey) { Text("Remove") }
            }
            when (val test = keyTest) {
                KeyTestState.Idle -> Unit
                KeyTestState.Testing -> Text("Testing…")
                is KeyTestState.Ok -> Text(
                    "The key works${test.username?.let { " for $it" }.orEmpty()}. Credits left: ${test.credits ?: "unknown"}.",
                    color = MaterialTheme.colorScheme.primary,
                )
                is KeyTestState.Failed -> Text(test.message, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { FileActions.openWebPage(context, "https://cloudconvert.com/dashboard/api/v2/keys") }) {
                Text("Get a free API key")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sandbox mode")
                    Text("Use CloudConvert's test environment (needs a sandbox key).", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.sandbox, onCheckedChange = viewModel::setSandbox)
            }

            HorizontalDivider()
            Text("Defaults", style = MaterialTheme.typography.titleMedium)
            Text("Image quality: ${quality.roundToInt()}")
            Slider(
                value = quality,
                onValueChange = { quality = it },
                valueRange = 1f..100f,
                onValueChangeFinished = { viewModel.setImageQuality(quality.roundToInt()) },
            )
            ChoiceChips("Audio bitrate", ConversionOptions.AUDIO_BITRATES.map { it to "$it kbps" }, settings.defaultAudioBitrateKbps) {
                viewModel.setAudioBitrate(it)
            }
            ChoiceChips(
                "Video quality",
                listOf<Pair<VideoQuality?, String>>(
                    null to "Keep",
                    VideoQuality.HIGH to "High",
                    VideoQuality.MEDIUM to "Medium",
                    VideoQuality.SMALL to "Small file",
                ),
                settings.defaultVideoQuality,
            ) { viewModel.setVideoQuality(it) }

            HorizontalDivider()
            OutlinedButton(onClick = viewModel::clearHistory) { Text("Clear history") }
        }
    }
}
```

- [ ] **Step 7: Implement routes, the nav host and the activity**

`app/src/main/java/dev/armen/fileconverter/ui/Routes.kt`:
```kotlin
package dev.armen.fileconverter.ui

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CONVERT = "convert"
    const val BATCH_ARG = "batch"
    const val PROGRESS = "progress/{$BATCH_ARG}"
    const val RESULT = "result/{$BATCH_ARG}"

    fun progress(batchTag: String) = "progress/${Uri.encode(batchTag)}"

    fun result(batchTag: String) = "result/${Uri.encode(batchTag)}"
}
```

`app/src/main/java/dev/armen/fileconverter/ui/AppNavHost.kt`:
```kotlin
package dev.armen.fileconverter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.armen.fileconverter.di.AppContainer
import dev.armen.fileconverter.ui.home.HomeScreen
import dev.armen.fileconverter.ui.home.HomeViewModel
import dev.armen.fileconverter.ui.settings.SettingsScreen
import dev.armen.fileconverter.ui.settings.SettingsViewModel

/** [shareSignal] increases each time files are shared into the app; it opens the Convert screen. */
@Composable
fun AppNavHost(container: AppContainer, shareSignal: Int) {
    val navController = rememberNavController()
    LaunchedEffect(shareSignal) {
        if (shareSignal > 0) navController.navigate(Routes.CONVERT) { launchSingleTop = true }
    }
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val viewModel = viewModel { HomeViewModel(container.history) }
            HomeScreen(
                viewModel = viewModel,
                onFilesPicked = { uris ->
                    container.selection.set(uris.mapNotNull { runCatching { container.inputResolver.resolve(it) }.getOrNull() })
                    navController.navigate(Routes.CONVERT)
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            val viewModel = viewModel { SettingsViewModel(container.settings, container::cloudClient, container.history) }
            SettingsScreen(viewModel, onBack = { navController.popBackStack() })
        }
        // Replaced in Task 15 (Convert) and Task 16 (Progress, Result).
        composable(Routes.CONVERT) { PlaceholderScreen("Convert") }
        composable(Routes.PROGRESS) { PlaceholderScreen("Progress") }
        composable(Routes.RESULT) { PlaceholderScreen("Result") }
    }
}

@Composable
private fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("$name screen") }
}
```

`app/src/main/java/dev/armen/fileconverter/MainActivity.kt` (replace the whole file):
```kotlin
package dev.armen.fileconverter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dev.armen.fileconverter.ui.AppNavHost
import dev.armen.fileconverter.ui.theme.FileConverterTheme

class MainActivity : ComponentActivity() {
    private var shareSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FileConverterApp).container
        if (savedInstanceState == null) handleShare(intent)
        setContent { FileConverterTheme { AppNavHost(container, shareSignal) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Files shared from other apps become the current selection; the URI grant lasts while we hold the intent. */
    private fun handleShare(intent: Intent?) {
        val uris = intent?.let(::sharedUris).orEmpty()
        if (uris.isEmpty()) return
        val container = (application as FileConverterApp).container
        container.selection.set(uris.mapNotNull { runCatching { container.inputResolver.resolve(it) }.getOrNull() })
        shareSignal++
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
```

- [ ] **Step 8: Build, install and look at the screens**

Run:
```bash
./gradlew testDebugUnitTest assembleDebug installDebug
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb"
"$ADB" shell am start -n dev.armen.fileconverter/.MainActivity
"$ADB" exec-out screencap -p > /tmp/home.png
```
Expected: build succeeds; `home.png` shows "File Converter", a "Pick files" button and "Nothing converted yet." (grant the notification prompt if shown). Tap the gear: Settings shows the API key field, Sandbox switch, quality slider and chips.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/ui app/src/main/java/dev/armen/fileconverter/MainActivity.kt app/src/test/java/dev/armen/fileconverter/ui
git commit -m "feat: add home, settings and share-to-app entry points

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Convert screen

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/ui/convert/ConvertPlanner.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/convert/ConvertViewModel.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/convert/ConvertScreen.kt`
- Modify: `app/src/main/java/dev/armen/fileconverter/ui/AppNavHost.kt` (replace the Convert placeholder)
- Test: `app/src/test/java/dev/armen/fileconverter/ui/convert/ConvertPlannerTest.kt`

**Interfaces:**
- Consumes: `FormatRegistry`, `FileFormat`, `FormatFamily`, `Engine`, `Fidelity`, `ConversionOptions`, `ConversionRequest`, `ConversionInput`, `FileNames`, `VideoQuality` (Tasks 2–3); `SelectionHolder`, `SettingsRepository`, `InputResolver` (Task 12); `ConversionScheduler` (Task 13); `ChoiceChips`, `Formatting`, `Routes` (Task 14).
- Produces:
  - `data class SelectedFile(displayName, sizeBytes: Long?, format: FileFormat?)`, `data class PreparedInput(path, displayName, format: FileFormat)`
  - `sealed interface SelectionCheck { Ok(targets: List<FileFormat>); Problem(message: String) }`, `enum class RouteBadge { OFFLINE, OFFLINE_BASIC, CLOUD, CLOUD_NEEDS_KEY }`, `enum class OptionSection { IMAGE_QUALITY, MAX_DIMENSION, AUDIO_BITRATE, SAMPLE_RATE, CHANNELS, VIDEO_RESOLUTION, VIDEO_QUALITY, TRIM, GIF }`
  - `object ConvertPlanner { check(files, sdkInt); badge(sources, target, sdkInt, hasApiKey); sections(sources, target); buildRequests(inputs, target, options); parseSeconds(text): Long?; trimError(startText, endText, forGif): String? }`
  - `ConvertViewModel(selection, settings, inputResolver, scheduler, queueDir: File, sdkInt = Build.VERSION.SDK_INT)` exposing `state: StateFlow<ConvertUiState>`, `batchStarted: Flow<String>` (batch tag)
  - `@Composable fun ConvertScreen(viewModel, onBack, onOpenSettings, onBatchStarted: (String) -> Unit)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/armen/fileconverter/ui/convert/ConvertPlannerTest.kt`:
```kotlin
package dev.armen.fileconverter.ui.convert

import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertPlannerTest {
    private val sdk = 34

    private fun file(name: String, format: FileFormat?) = SelectedFile(name, 100, format)

    private fun targets(vararg files: SelectedFile) = (ConvertPlanner.check(files.toList(), sdk) as SelectionCheck.Ok).targets

    @Test fun `empty selection is a problem`() =
        assertTrue(ConvertPlanner.check(emptyList(), sdk) is SelectionCheck.Problem)

    @Test fun `unknown file type names the file`() {
        val check = ConvertPlanner.check(listOf(file("a.png", FileFormat.PNG), file("thing.xyz", null)), sdk)
        assertTrue((check as SelectionCheck.Problem).message.contains("thing.xyz"))
    }

    @Test fun `mixed families are a problem`() =
        assertTrue(ConvertPlanner.check(listOf(file("a.png", FileFormat.PNG), file("b.mp3", FileFormat.MP3)), sdk) is SelectionCheck.Problem)

    @Test fun `mixed image formats can target either of them`() {
        val targets = targets(file("a.jpg", FileFormat.JPG), file("b.png", FileFormat.PNG))
        assertTrue(targets.containsAll(listOf(FileFormat.JPG, FileFormat.PNG, FileFormat.WEBP, FileFormat.PDF)))
        assertFalse(FileFormat.MP3 in targets)
    }

    @Test fun `files never target their own single format`() =
        assertFalse(FileFormat.JPG in targets(file("a.jpg", FileFormat.JPG), file("b.jpg", FileFormat.JPG)))

    @Test fun `route badges`() {
        assertEquals(RouteBadge.OFFLINE_BASIC, ConvertPlanner.badge(listOf(FileFormat.DOCX), FileFormat.PDF, sdk, hasApiKey = false))
        assertEquals(RouteBadge.CLOUD, ConvertPlanner.badge(listOf(FileFormat.DOCX), FileFormat.PDF, sdk, hasApiKey = true))
        assertEquals(RouteBadge.CLOUD_NEEDS_KEY, ConvertPlanner.badge(listOf(FileFormat.DOCX), FileFormat.ODT, sdk, hasApiKey = false))
        assertEquals(RouteBadge.OFFLINE, ConvertPlanner.badge(listOf(FileFormat.MP4), FileFormat.MP3, sdk, hasApiKey = false))
    }

    @Test fun `option sections per target`() {
        assertEquals(setOf(OptionSection.GIF, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.MP4), FileFormat.GIF))
        assertEquals(setOf(OptionSection.AUDIO_BITRATE, OptionSection.CHANNELS, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.WAV), FileFormat.OPUS))
        assertEquals(setOf(OptionSection.SAMPLE_RATE, OptionSection.CHANNELS, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.WAV), FileFormat.FLAC))
        assertEquals(setOf(OptionSection.VIDEO_RESOLUTION, OptionSection.VIDEO_QUALITY, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.MP4), FileFormat.MKV))
        assertEquals(setOf(OptionSection.IMAGE_QUALITY, OptionSection.MAX_DIMENSION), ConvertPlanner.sections(listOf(FileFormat.PNG), FileFormat.JPG))
        assertEquals(setOf(OptionSection.MAX_DIMENSION), ConvertPlanner.sections(listOf(FileFormat.PNG), FileFormat.ICO))
        assertEquals(emptySet<OptionSection>(), ConvertPlanner.sections(listOf(FileFormat.PNG), FileFormat.PDF))
        assertEquals(setOf(OptionSection.IMAGE_QUALITY), ConvertPlanner.sections(listOf(FileFormat.PDF), FileFormat.JPG))
        assertEquals(emptySet<OptionSection>(), ConvertPlanner.sections(listOf(FileFormat.DOCX), FileFormat.PDF))
    }

    @Test fun `150 photos to pdf become one request`() {
        val inputs = List(150) { PreparedInput("/q/$it.jpg", "photo-$it.jpg", FileFormat.JPG) }
        val requests = ConvertPlanner.buildRequests(inputs, FileFormat.PDF, ConversionOptions())
        assertEquals(1, requests.size)
        assertEquals(150, requests.single().inputs.size)
        assertEquals(FileFormat.JPG, requests.single().source)
    }

    @Test fun `other batches become one request per file and skip files already in the target format`() {
        val inputs = listOf(
            PreparedInput("/q/0.jpg", "a.jpg", FileFormat.JPG),
            PreparedInput("/q/1.png", "b.png", FileFormat.PNG),
            PreparedInput("/q/2.webp", "c.webp", FileFormat.WEBP),
        )
        val requests = ConvertPlanner.buildRequests(inputs, FileFormat.JPG, ConversionOptions())
        assertEquals(listOf("b.png", "c.webp"), requests.map { it.inputs.single().displayName })
        assertEquals(listOf(FileFormat.PNG, FileFormat.WEBP), requests.map { it.source })
    }

    @Test fun `parses seconds`() {
        assertEquals(1_500L, ConvertPlanner.parseSeconds("1.5"))
        assertEquals(1_500L, ConvertPlanner.parseSeconds(" 1,5 "))
        assertNull(ConvertPlanner.parseSeconds("-1"))
        assertNull(ConvertPlanner.parseSeconds("abc"))
        assertNull(ConvertPlanner.parseSeconds(""))
    }

    @Test fun `validates trim fields`() {
        assertNull(ConvertPlanner.trimError("", "", forGif = true))
        assertNull(ConvertPlanner.trimError("2", "10", forGif = false))
        assertEquals("End must be after start.", ConvertPlanner.trimError("5", "2", forGif = false))
        assertEquals("Start time must be a number of seconds.", ConvertPlanner.trimError("x", "", forGif = false))
        assertEquals("GIFs can be at most 30 seconds long.", ConvertPlanner.trimError("0", "45", forGif = true))
        assertNull(ConvertPlanner.trimError("0", "45", forGif = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.ui.convert.ConvertPlannerTest"`
Expected: FAIL — `Unresolved reference: ConvertPlanner`.

- [ ] **Step 3: Implement the planner**

`app/src/main/java/dev/armen/fileconverter/ui/convert/ConvertPlanner.kt`:
```kotlin
package dev.armen.fileconverter.ui.convert

import dev.armen.fileconverter.domain.ConversionInput
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.domain.Fidelity
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.FormatRegistry
import kotlin.math.roundToLong

data class SelectedFile(val displayName: String, val sizeBytes: Long?, val format: FileFormat?)

/** An input already copied into app storage. */
data class PreparedInput(val path: String, val displayName: String, val format: FileFormat)

sealed interface SelectionCheck {
    data class Ok(val targets: List<FileFormat>) : SelectionCheck
    data class Problem(val message: String) : SelectionCheck
}

enum class RouteBadge { OFFLINE, OFFLINE_BASIC, CLOUD, CLOUD_NEEDS_KEY }

enum class OptionSection { IMAGE_QUALITY, MAX_DIMENSION, AUDIO_BITRATE, SAMPLE_RATE, CHANNELS, VIDEO_RESOLUTION, VIDEO_QUALITY, TRIM, GIF }

/** Pure decisions behind the Convert screen. */
object ConvertPlanner {
    private val LOSSY_AUDIO = setOf(FileFormat.MP3, FileFormat.M4A, FileFormat.AAC, FileFormat.OGG, FileFormat.OPUS, FileFormat.WMA)

    fun check(files: List<SelectedFile>, sdkInt: Int): SelectionCheck {
        if (files.isEmpty()) return SelectionCheck.Problem("No files selected.")
        files.firstOrNull { it.format == null }?.let {
            return SelectionCheck.Problem("${it.displayName} isn't a file type this app supports.")
        }
        val formats = files.mapNotNull { it.format }
        if (formats.map { it.family }.toSet().size > 1) {
            return SelectionCheck.Problem("Pick files of one kind at a time, for example only images or only videos.")
        }
        // A target is offered when every file can reach it (or already is it) and at least one file needs converting.
        val targets = FileFormat.entries.filter { target ->
            formats.any { it != target } &&
                formats.all { it == target || FormatRegistry.routesFor(it, target, sdkInt).isNotEmpty() }
        }
        return if (targets.isEmpty()) SelectionCheck.Problem("These files have no output format in common.") else SelectionCheck.Ok(targets)
    }

    fun badge(sources: List<FileFormat>, target: FileFormat, sdkInt: Int, hasApiKey: Boolean): RouteBadge {
        val source = sources.firstOrNull { it != target } ?: return RouteBadge.OFFLINE
        val route = FormatRegistry.chooseRoute(source, target, sdkInt, cloudAvailable = hasApiKey) ?: return RouteBadge.CLOUD_NEEDS_KEY
        return when {
            route.engine == Engine.CLOUD -> RouteBadge.CLOUD
            route.fidelity == Fidelity.BASIC -> RouteBadge.OFFLINE_BASIC
            else -> RouteBadge.OFFLINE
        }
    }

    fun sections(sources: List<FileFormat>, target: FileFormat): Set<OptionSection> {
        val family = sources.firstOrNull()?.family ?: return emptySet()
        return when {
            family == FormatFamily.VIDEO && target == FileFormat.GIF -> setOf(OptionSection.GIF, OptionSection.TRIM)
            target.family == FormatFamily.AUDIO -> buildSet {
                if (target in LOSSY_AUDIO) add(OptionSection.AUDIO_BITRATE)
                if (target != FileFormat.OPUS) add(OptionSection.SAMPLE_RATE)
                add(OptionSection.CHANNELS)
                add(OptionSection.TRIM)
            }
            target.family == FormatFamily.VIDEO -> setOf(OptionSection.VIDEO_RESOLUTION, OptionSection.VIDEO_QUALITY, OptionSection.TRIM)
            family == FormatFamily.IMAGE && target == FileFormat.PDF -> emptySet()
            family == FormatFamily.IMAGE -> buildSet {
                if (target == FileFormat.JPG || target == FileFormat.WEBP) add(OptionSection.IMAGE_QUALITY)
                add(OptionSection.MAX_DIMENSION)
            }
            sources.first() == FileFormat.PDF && target == FileFormat.JPG -> setOf(OptionSection.IMAGE_QUALITY)
            else -> emptySet()
        }
    }

    /** Several images -> PDF become one merged request; everything else is one request per file. */
    fun buildRequests(inputs: List<PreparedInput>, target: FileFormat, options: ConversionOptions): List<ConversionRequest> {
        val toConvert = inputs.filter { it.format != target }
        if (target == FileFormat.PDF && toConvert.size > 1 && toConvert.all { it.format.family == FormatFamily.IMAGE }) {
            return listOf(
                ConversionRequest(toConvert.map { ConversionInput(it.path, it.displayName) }, toConvert.first().format, target, options),
            )
        }
        return toConvert.map { ConversionRequest(listOf(ConversionInput(it.path, it.displayName)), it.format, target, options) }
    }

    fun parseSeconds(text: String): Long? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (value < 0 || value.isNaN() || value.isInfinite()) return null
        return (value * 1000).roundToLong()
    }

    /** Null when valid. Blank start means 0; blank end means "to the end" (or the default GIF length). */
    fun trimError(startText: String, endText: String, forGif: Boolean): String? {
        val start = if (startText.isBlank()) 0L else parseSeconds(startText) ?: return "Start time must be a number of seconds."
        val end = if (endText.isBlank()) null else parseSeconds(endText) ?: return "End time must be a number of seconds."
        if (end != null && end <= start) return "End must be after start."
        if (forGif && end != null && end - start > ConversionOptions.MAX_GIF_RANGE_MS) return "GIFs can be at most 30 seconds long."
        return null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.ui.convert.ConvertPlannerTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Implement the view model**

`app/src/main/java/dev/armen/fileconverter/ui/convert/ConvertViewModel.kt`:
```kotlin
package dev.armen.fileconverter.ui.convert

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.data.InputResolver
import dev.armen.fileconverter.data.SelectionHolder
import dev.armen.fileconverter.data.SettingsRepository
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.work.ConversionScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class ConvertUiState(
    val files: List<SelectedFile> = emptyList(),
    val check: SelectionCheck? = null,
    val target: FileFormat? = null,
    val options: ConversionOptions = ConversionOptions(),
    val trimStart: String = "",
    val trimEnd: String = "",
    val hasApiKey: Boolean = false,
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val sources: List<FileFormat> get() = files.mapNotNull { it.format }
}

class ConvertViewModel(
    private val selection: SelectionHolder,
    private val settings: SettingsRepository,
    private val inputResolver: InputResolver,
    private val scheduler: ConversionScheduler,
    private val queueDir: File,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {

    private val _state = MutableStateFlow(ConvertUiState())
    val state: StateFlow<ConvertUiState> = _state.asStateFlow()

    private val _batchStarted = Channel<String>(Channel.BUFFERED)
    /** Emits the batch tag once inputs are copied and the work is enqueued. */
    val batchStarted: Flow<String> = _batchStarted.receiveAsFlow()

    init {
        viewModelScope.launch {
            val defaults = settings.defaultOptions()
            _state.update { it.copy(options = defaults) }
        }
        viewModelScope.launch {
            selection.inputs.collect { inputs ->
                val files = inputs.map { SelectedFile(it.displayName, it.sizeBytes, it.format) }
                _state.update { it.copy(files = files, check = ConvertPlanner.check(files, sdkInt), target = null, error = null) }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(hasApiKey = s.hasApiKey) } }
        }
    }

    fun badge(state: ConvertUiState): RouteBadge? =
        state.target?.let { ConvertPlanner.badge(state.sources, it, sdkInt, state.hasApiKey) }

    fun sections(state: ConvertUiState): Set<OptionSection> =
        state.target?.let { ConvertPlanner.sections(state.sources, it) }.orEmpty()

    fun selectTarget(target: FileFormat) = _state.update { it.copy(target = target, error = null) }

    fun updateOptions(transform: (ConversionOptions) -> ConversionOptions) = _state.update { it.copy(options = transform(it.options)) }

    fun setTrimStart(text: String) = _state.update { it.copy(trimStart = text, error = null) }

    fun setTrimEnd(text: String) = _state.update { it.copy(trimEnd = text, error = null) }

    fun convert() {
        val current = _state.value
        val target = current.target ?: return
        if (current.preparing) return
        val usesTrim = OptionSection.TRIM in sections(current)
        if (usesTrim) {
            val forGif = target == FileFormat.GIF && current.sources.firstOrNull()?.family == FormatFamily.VIDEO
            ConvertPlanner.trimError(current.trimStart, current.trimEnd, forGif)?.let { message ->
                _state.update { it.copy(error = message) }
                return
            }
        }
        val options = if (usesTrim) {
            current.options.copy(trimStartMs = ConvertPlanner.parseSeconds(current.trimStart), trimEndMs = ConvertPlanner.parseSeconds(current.trimEnd))
        } else {
            current.options.copy(trimStartMs = null, trimEndMs = null)
        }
        val inputs = selection.inputs.value.filter { it.format != null && it.format != target }
        viewModelScope.launch {
            _state.update { it.copy(preparing = true, error = null) }
            val batchId = UUID.randomUUID().toString()
            val batchDir = File(queueDir, batchId)
            try {
                val tag = withContext(Dispatchers.IO) {
                    batchDir.mkdirs()
                    // Copy now: picker and share URI grants may expire before a queued worker runs.
                    val prepared = inputs.mapIndexed { index, input ->
                        val dest = File(batchDir, "$index-${FileNames.sanitize(input.displayName)}")
                        inputResolver.copyToFile(input.uri, dest)
                        PreparedInput(dest.absolutePath, input.displayName, requireNotNull(input.format))
                    }
                    scheduler.enqueue(batchId, batchDir, ConvertPlanner.buildRequests(prepared, target, options))
                }
                _batchStarted.send(tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { batchDir.deleteRecursively() }
                _state.update { it.copy(error = "Couldn't read the selected files: ${e.message ?: e::class.java.simpleName}") }
            } finally {
                _state.update { it.copy(preparing = false) }
            }
        }
    }
}
```

- [ ] **Step 6: Implement the screen**

`app/src/main/java/dev/armen/fileconverter/ui/convert/ConvertScreen.kt`:
```kotlin
package dev.armen.fileconverter.ui.convert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.VideoQuality
import dev.armen.fileconverter.ui.common.ChoiceChips
import dev.armen.fileconverter.ui.common.Formatting
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    viewModel: ConvertViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onBatchStarted: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.batchStarted.collect { onBatchStarted(it) } }
    val badge = viewModel.badge(state)
    val sections = viewModel.sections(state)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convert") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                    if (badge == RouteBadge.CLOUD_NEEDS_KEY) {
                        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Add CloudConvert API key") }
                    } else {
                        Button(
                            onClick = viewModel::convert,
                            enabled = state.target != null && !state.preparing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.preparing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Preparing…")
                            } else {
                                Text(state.target?.let { "Convert to ${it.label}" } ?: "Choose a format")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilesCard(state.files)
            when (val check = state.check) {
                null -> Unit
                is SelectionCheck.Problem -> Text(check.message, color = MaterialTheme.colorScheme.error)
                is SelectionCheck.Ok -> {
                    TargetPicker(check.targets, state.target, viewModel::selectTarget)
                    val target = state.target
                    if (target != null) {
                        badge?.let { BadgeText(it) }
                        Notes(state, target)
                        OptionsPanel(sections, state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesCard(files: List<SelectedFile>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (files.size == 1) "1 file" else "${files.size} files", style = MaterialTheme.typography.titleMedium)
            files.take(3).forEach { file ->
                Text(
                    listOf(file.displayName, file.format?.label ?: "unknown type", Formatting.bytes(file.sizeBytes))
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (files.size > 3) Text("+ ${files.size - 3} more", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetPicker(targets: List<FileFormat>, selected: FileFormat?, onSelect: (FileFormat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Convert to", style = MaterialTheme.typography.titleMedium)
        targets.groupBy { it.family }.forEach { (family, formats) ->
            Text(familyLabel(family), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEach { format ->
                    FilterChip(selected = format == selected, onClick = { onSelect(format) }, label = { Text(format.label) })
                }
            }
        }
    }
}

private fun familyLabel(family: FormatFamily): String = when (family) {
    FormatFamily.IMAGE -> "Image"
    FormatFamily.AUDIO -> "Audio"
    FormatFamily.VIDEO -> "Video"
    FormatFamily.DOCUMENT -> "Document"
}

@Composable
private fun BadgeText(badge: RouteBadge) {
    val (text, color) = when (badge) {
        RouteBadge.OFFLINE -> "On this phone · works offline" to MaterialTheme.colorScheme.onSurfaceVariant
        RouteBadge.OFFLINE_BASIC ->
            "Offline · basic (text only). Add a CloudConvert key in Settings to keep the layout." to MaterialTheme.colorScheme.tertiary
        RouteBadge.CLOUD -> "☁ Uploads to CloudConvert" to MaterialTheme.colorScheme.primary
        RouteBadge.CLOUD_NEEDS_KEY -> "☁ Needs a CloudConvert API key" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Notes(state: ConvertUiState, target: FileFormat) {
    val skipped = state.sources.count { it == target }
    val converting = state.sources.size - skipped
    if (skipped > 0) {
        Text("$skipped file(s) already in ${target.label} will be skipped.", style = MaterialTheme.typography.bodySmall)
    }
    if (target == FileFormat.PDF && converting > 1 && state.sources.all { it.family == FormatFamily.IMAGE }) {
        Text("All $converting images will be combined into one PDF.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OptionsPanel(sections: Set<OptionSection>, state: ConvertUiState, viewModel: ConvertViewModel) {
    if (sections.isEmpty()) return
    val o = state.options
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Options", style = MaterialTheme.typography.titleMedium)
        if (OptionSection.IMAGE_QUALITY in sections) {
            Text("Quality: ${o.imageQuality}")
            Slider(
                value = o.imageQuality.toFloat(),
                onValueChange = { v -> viewModel.updateOptions { it.copy(imageQuality = v.roundToInt()) } },
                valueRange = 1f..100f,
            )
        }
        if (OptionSection.MAX_DIMENSION in sections) {
            ChoiceChips("Size", listOf<Pair<Int?, String>>(null to "Original") + ConversionOptions.MAX_DIMENSIONS.map { it to "$it px" }, o.maxDimension) { v ->
                viewModel.updateOptions { it.copy(maxDimension = v) }
            }
        }
        if (OptionSection.AUDIO_BITRATE in sections) {
            ChoiceChips("Bitrate", ConversionOptions.AUDIO_BITRATES.map { it to "$it kbps" }, o.audioBitrateKbps) { v ->
                viewModel.updateOptions { it.copy(audioBitrateKbps = v) }
            }
        }
        if (OptionSection.SAMPLE_RATE in sections) {
            ChoiceChips("Sample rate", listOf<Pair<Int?, String>>(null to "Keep", 44_100 to "44.1 kHz", 48_000 to "48 kHz"), o.sampleRateHz) { v ->
                viewModel.updateOptions { it.copy(sampleRateHz = v) }
            }
        }
        if (OptionSection.CHANNELS in sections) {
            ChoiceChips("Channels", listOf<Pair<Int?, String>>(null to "Keep", 1 to "Mono", 2 to "Stereo"), o.channels) { v ->
                viewModel.updateOptions { it.copy(channels = v) }
            }
        }
        if (OptionSection.VIDEO_RESOLUTION in sections) {
            ChoiceChips("Resolution", listOf<Pair<Int?, String>>(null to "Keep") + ConversionOptions.VIDEO_HEIGHTS.map { it to "${it}p" }, o.videoHeight) { v ->
                viewModel.updateOptions { it.copy(videoHeight = v) }
            }
        }
        if (OptionSection.VIDEO_QUALITY in sections) {
            ChoiceChips(
                "Quality",
                listOf<Pair<VideoQuality?, String>>(null to "Keep", VideoQuality.HIGH to "High", VideoQuality.MEDIUM to "Medium", VideoQuality.SMALL to "Small file"),
                o.videoQuality,
            ) { v -> viewModel.updateOptions { it.copy(videoQuality = v) } }
        }
        if (OptionSection.GIF in sections) {
            ChoiceChips("Frames per second", ConversionOptions.GIF_FPS.map { it to "$it fps" }, o.gifFps) { v ->
                viewModel.updateOptions { it.copy(gifFps = v) }
            }
            ChoiceChips("Width", ConversionOptions.GIF_WIDTHS.map { it to "$it px" }, o.gifWidth) { v ->
                viewModel.updateOptions { it.copy(gifWidth = v) }
            }
        }
        if (OptionSection.TRIM in sections) {
            val gif = OptionSection.GIF in sections
            Text(if (gif) "Clip (max 30 s; defaults to the first 5 s)" else "Trim (optional)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.trimStart,
                    onValueChange = viewModel::setTrimStart,
                    label = { Text("Start (s)") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.trimEnd,
                    onValueChange = viewModel::setTrimEnd,
                    label = { Text("End (s)") },
                    placeholder = { Text(if (gif) "5" else "end") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
```

- [ ] **Step 7: Wire the Convert destination**

In `app/src/main/java/dev/armen/fileconverter/ui/AppNavHost.kt`, add the imports:
```kotlin
import dev.armen.fileconverter.ui.convert.ConvertScreen
import dev.armen.fileconverter.ui.convert.ConvertViewModel
```
and replace
```kotlin
        composable(Routes.CONVERT) { PlaceholderScreen("Convert") }
```
with
```kotlin
        composable(Routes.CONVERT) {
            val viewModel = viewModel {
                ConvertViewModel(container.selection, container.settings, container.inputResolver, container.scheduler, container.queueDir())
            }
            ConvertScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onBatchStarted = { tag -> navController.navigate(Routes.progress(tag)) },
            )
        }
```

- [ ] **Step 8: Build and try it**

Run:
```bash
./gradlew testDebugUnitTest installDebug
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb"
"$ADB" shell "printf 'name;city\nAnn;Zürich\n' > /sdcard/Download/people.csv"
"$ADB" shell am start -n dev.armen.fileconverter/.MainActivity
```
Expected: tests pass. In the app: Pick files → Download → `people.csv` opens Convert with "1 file · people.csv · CSV", Document chips (XLSX, XLS, ODS, PDF), choosing XLSX shows "On this phone · works offline", choosing ODS without a key shows "☁ Needs a CloudConvert API key" and an "Add CloudConvert API key" button. Tapping Convert to XLSX navigates to the Progress placeholder.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/ui app/src/test/java/dev/armen/fileconverter/ui
git commit -m "feat: add convert screen with targets, options and route badges

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Progress and Result screens

**Files:**
- Create: `app/src/main/java/dev/armen/fileconverter/ui/batch/BatchMapper.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/batch/BatchViewModel.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/batch/ProgressScreen.kt`
- Create: `app/src/main/java/dev/armen/fileconverter/ui/batch/ResultScreen.kt`
- Modify: `app/src/main/java/dev/armen/fileconverter/ui/AppNavHost.kt` (replace whole file)
- Test: `app/src/test/java/dev/armen/fileconverter/ui/batch/BatchMapperTest.kt`

**Interfaces:**
- Consumes: `ConversionWorker` keys and stages, `ConversionScheduler` (Task 13); `ErrorKind` (Task 3); `FileActions`, `Formatting`, `Routes`, `AppNavHost` (Task 14); `ConvertScreen`/`ConvertViewModel` (Task 15).
- Produces:
  - `data class SavedOutput(uri: String, name: String, sizeBytes: Long)`, `data class ErrorInfo(kind: ErrorKind, message: String, details: String?)`, `enum class ItemState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }`, `data class BatchItem(id, index, label, state, progress, stage, outputs, totalOutputs, error, basic) { val finished }`
  - `object BatchMapper { fun map(infos: List<WorkInfo>): List<BatchItem>; fun toItem(info: WorkInfo): BatchItem; fun overallProgress(items): Float; fun allFinished(items): Boolean }`
  - `class BatchViewModel(tag: String, scheduler: ConversionScheduler, queueDir: File) { val items: StateFlow<List<BatchItem>>; fun cancel() }`
  - `ProgressScreen(viewModel, onFinished, onBack)`, `ResultScreen(viewModel, onOpenSettings, onRetry, onDone)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/armen/fileconverter/ui/batch/BatchMapperTest.kt`:
```kotlin
package dev.armen.fileconverter.ui.batch

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.work.ConversionScheduler
import dev.armen.fileconverter.work.ConversionWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BatchMapperTest {
    private fun info(state: WorkInfo.State, index: Int, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = setOf(
            ConversionScheduler.TAG_ALL,
            "${ConversionScheduler.INDEX_PREFIX}$index",
            "${ConversionScheduler.LABEL_PREFIX}file$index.docx → PDF",
        ),
        outputData = output,
        progress = progress,
    )

    @Test fun `sorts by queue index and reads the label`() {
        val items = BatchMapper.map(listOf(info(WorkInfo.State.ENQUEUED, 2), info(WorkInfo.State.RUNNING, 0), info(WorkInfo.State.BLOCKED, 1)))
        assertEquals(listOf(0, 1, 2), items.map { it.index })
        assertEquals("file0.docx → PDF", items[0].label)
        assertEquals(listOf(ItemState.RUNNING, ItemState.QUEUED, ItemState.QUEUED), items.map { it.state })
    }

    @Test fun `succeeded work carrying an error is a failure`() {
        val output = workDataOf(
            ConversionWorker.KEY_ERROR_KIND to "MISSING_API_KEY",
            ConversionWorker.KEY_ERROR_MESSAGE to "Add your CloudConvert API key in Settings.",
        )
        val item = BatchMapper.toItem(info(WorkInfo.State.SUCCEEDED, 0, output = output))
        assertEquals(ItemState.FAILED, item.state)
        assertEquals(ErrorInfo(ErrorKind.MISSING_API_KEY, "Add your CloudConvert API key in Settings.", null), item.error)
        assertTrue(item.outputs.isEmpty())
    }

    @Test fun `done work lists its outputs and total`() {
        val output = Data.Builder()
            .putStringArray(ConversionWorker.KEY_OUTPUT_URIS, arrayOf("content://a/1", "content://a/2"))
            .putStringArray(ConversionWorker.KEY_OUTPUT_NAMES, arrayOf("doc-1.png", "doc-2.png"))
            .putLongArray(ConversionWorker.KEY_OUTPUT_SIZES, longArrayOf(10, 20))
            .putInt(ConversionWorker.KEY_OUTPUT_TOTAL, 45)
            .putBoolean(ConversionWorker.KEY_BASIC, true)
            .build()
        val item = BatchMapper.toItem(info(WorkInfo.State.SUCCEEDED, 0, output = output))
        assertEquals(ItemState.DONE, item.state)
        assertEquals(listOf(SavedOutput("content://a/1", "doc-1.png", 10), SavedOutput("content://a/2", "doc-2.png", 20)), item.outputs)
        assertEquals(45, item.totalOutputs)
        assertTrue(item.basic)
        assertEquals(1f, item.progress)
    }

    @Test fun `running work reports progress and stage`() {
        val progress = workDataOf(ConversionWorker.KEY_PROGRESS to 0.25f, ConversionWorker.KEY_STAGE to ConversionWorker.STAGE_CONVERTING)
        val item = BatchMapper.toItem(info(WorkInfo.State.RUNNING, 0, progress = progress))
        assertEquals(0.25f, item.progress)
        assertEquals(ConversionWorker.STAGE_CONVERTING, item.stage)
    }

    @Test fun `failed work without details still has an error`() =
        assertEquals(ErrorKind.ENGINE_FAILED, BatchMapper.toItem(info(WorkInfo.State.FAILED, 0)).error?.kind)

    @Test fun `overall progress counts finished items as complete`() {
        val done = BatchMapper.toItem(info(WorkInfo.State.SUCCEEDED, 0))
        val half = BatchMapper.toItem(info(WorkInfo.State.RUNNING, 1, progress = workDataOf(ConversionWorker.KEY_PROGRESS to 0.5f)))
        assertEquals(0.75f, BatchMapper.overallProgress(listOf(done, half)), 0.001f)
        assertFalse(BatchMapper.allFinished(listOf(done, half)))
        assertTrue(BatchMapper.allFinished(listOf(done, BatchMapper.toItem(info(WorkInfo.State.CANCELLED, 1)))))
        assertFalse(BatchMapper.allFinished(emptyList()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.ui.batch.BatchMapperTest"`
Expected: FAIL — `Unresolved reference: BatchMapper`.

- [ ] **Step 3: Implement the mapper and view model**

`app/src/main/java/dev/armen/fileconverter/ui/batch/BatchMapper.kt`:
```kotlin
package dev.armen.fileconverter.ui.batch

import androidx.work.WorkInfo
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.work.ConversionScheduler
import dev.armen.fileconverter.work.ConversionWorker
import java.util.UUID

data class SavedOutput(val uri: String, val name: String, val sizeBytes: Long)

data class ErrorInfo(val kind: ErrorKind, val message: String, val details: String?)

enum class ItemState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class BatchItem(
    val id: UUID,
    val index: Int,
    val label: String,
    val state: ItemState,
    val progress: Float,
    val stage: String?,
    val outputs: List<SavedOutput>,
    val totalOutputs: Int,
    val error: ErrorInfo?,
    val basic: Boolean,
) {
    val finished: Boolean get() = state == ItemState.DONE || state == ItemState.FAILED || state == ItemState.CANCELLED
}

/** Turns WorkManager's view of a batch into what the screens show. */
object BatchMapper {

    fun map(infos: List<WorkInfo>): List<BatchItem> = infos.map(::toItem).sortedBy { it.index }

    fun toItem(info: WorkInfo): BatchItem {
        val out = info.outputData
        val errorKind = out.getString(ConversionWorker.KEY_ERROR_KIND)?.let { name -> ErrorKind.entries.firstOrNull { it.name == name } }
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ItemState.QUEUED
            WorkInfo.State.RUNNING -> ItemState.RUNNING
            WorkInfo.State.SUCCEEDED -> if (errorKind != null) ItemState.FAILED else ItemState.DONE
            WorkInfo.State.FAILED -> ItemState.FAILED
            WorkInfo.State.CANCELLED -> ItemState.CANCELLED
        }
        val uris = out.getStringArray(ConversionWorker.KEY_OUTPUT_URIS).orEmpty()
        val names = out.getStringArray(ConversionWorker.KEY_OUTPUT_NAMES).orEmpty()
        val sizes = out.getLongArray(ConversionWorker.KEY_OUTPUT_SIZES) ?: LongArray(0)
        val outputs = if (state == ItemState.DONE) {
            uris.indices.map { SavedOutput(uris[it], names.getOrElse(it) { "file" }, sizes.getOrElse(it) { 0L }) }
        } else {
            emptyList()
        }
        val error = when {
            errorKind != null -> ErrorInfo(
                errorKind,
                out.getString(ConversionWorker.KEY_ERROR_MESSAGE) ?: "Conversion failed.",
                out.getString(ConversionWorker.KEY_ERROR_DETAILS),
            )
            info.state == WorkInfo.State.FAILED -> ErrorInfo(ErrorKind.ENGINE_FAILED, "Conversion failed.", null)
            else -> null
        }
        return BatchItem(
            id = info.id,
            index = tagValue(info, ConversionScheduler.INDEX_PREFIX)?.toIntOrNull() ?: 0,
            label = tagValue(info, ConversionScheduler.LABEL_PREFIX) ?: "File",
            state = state,
            progress = if (state == ItemState.DONE) 1f else info.progress.getFloat(ConversionWorker.KEY_PROGRESS, 0f),
            stage = info.progress.getString(ConversionWorker.KEY_STAGE),
            outputs = outputs,
            totalOutputs = out.getInt(ConversionWorker.KEY_OUTPUT_TOTAL, outputs.size),
            error = error,
            basic = out.getBoolean(ConversionWorker.KEY_BASIC, false),
        )
    }

    fun overallProgress(items: List<BatchItem>): Float =
        if (items.isEmpty()) 0f else items.sumOf { (if (it.finished) 1f else it.progress).toDouble() }.toFloat() / items.size

    fun allFinished(items: List<BatchItem>): Boolean = items.isNotEmpty() && items.all { it.finished }

    private fun tagValue(info: WorkInfo, prefix: String): String? =
        info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
}
```

`app/src/main/java/dev/armen/fileconverter/ui/batch/BatchViewModel.kt`:
```kotlin
package dev.armen.fileconverter.ui.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.work.ConversionScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import kotlin.concurrent.thread

class BatchViewModel(
    private val tag: String,
    private val scheduler: ConversionScheduler,
    private val queueDir: File,
) : ViewModel() {

    val items: StateFlow<List<BatchItem>> =
        scheduler.observe(tag).map(BatchMapper::map).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel() {
        scheduler.cancel(tag)
        // Not viewModelScope: the screen closes right after cancelling, which would cancel the cleanup.
        thread(name = "batch-cleanup") { File(queueDir, ConversionScheduler.batchId(tag)).deleteRecursively() }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.armen.fileconverter.ui.batch.BatchMapperTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Implement the screens**

`app/src/main/java/dev/armen/fileconverter/ui/batch/ProgressScreen.kt`:
```kotlin
package dev.armen.fileconverter.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.work.ConversionWorker
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(viewModel: BatchViewModel, onFinished: () -> Unit, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val finished = BatchMapper.allFinished(items)
    LaunchedEffect(finished) { if (finished) onFinished() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Converting") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(progress = { BatchMapper.overallProgress(items) }, modifier = Modifier.fillMaxWidth())
            Text(
                "You can leave this screen. Conversions continue in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item -> ProgressRow(item) }
            }
            OutlinedButton(
                onClick = {
                    viewModel.cancel()
                    onBack()
                },
                enabled = !finished,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun ProgressRow(item: BatchItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (item.state) {
            ItemState.QUEUED -> Text("Waiting…", style = MaterialTheme.typography.bodySmall)
            ItemState.RUNNING -> when (item.stage) {
                ConversionWorker.STAGE_WAITING -> {
                    Text("Waiting for the previous file…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ConversionWorker.STAGE_SAVING -> {
                    Text("Saving…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> {
                    Text("${(item.progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            ItemState.DONE -> Text("Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            ItemState.FAILED -> Text(item.error?.message ?: "Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            ItemState.CANCELLED -> Text("Cancelled", style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

`app/src/main/java/dev/armen/fileconverter/ui/batch/ResultScreen.kt`:
```kotlin
package dev.armen.fileconverter.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.ui.common.FileActions
import dev.armen.fileconverter.ui.common.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(viewModel: BatchViewModel, onOpenSettings: () -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsStateWithLifecycle()
    val allOutputs = items.flatMap { it.outputs }

    Scaffold(
        topBar = { TopAppBar(title = { Text(resultTitle(items)) }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (allOutputs.size > 1) {
                        OutlinedButton(
                            onClick = { FileActions.share(context, allOutputs.map { it.uri.toUri() }, "*/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Share all (${allOutputs.size})") }
                    }
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Convert another") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item -> ResultCard(item, onOpenSettings, onRetry) }
        }
    }
}

private fun resultTitle(items: List<BatchItem>): String {
    val failed = items.count { it.state == ItemState.FAILED }
    return when {
        items.isEmpty() -> "Results"
        failed == 0 -> "Done"
        failed == items.size -> "Conversion failed"
        else -> "Done with $failed error(s)"
    }
}

@Composable
private fun ResultCard(item: BatchItem, onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var showDetails by rememberSaveable(item.id.toString()) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleSmall)
            when (item.state) {
                ItemState.DONE -> {
                    if (item.basic) {
                        Text(
                            "Offline basic conversion: text only, layout not kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    item.outputs.forEach { output ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(output.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Formatting.bytes(output.sizeBytes), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { FileActions.open(context, output.uri.toUri(), FileActions.mimeTypeFor(output.name)) }) { Text("Open") }
                            TextButton(onClick = { FileActions.share(context, listOf(output.uri.toUri()), FileActions.mimeTypeFor(output.name)) }) { Text("Share") }
                        }
                    }
                    if (item.totalOutputs > item.outputs.size) {
                        Text("+ ${item.totalOutputs - item.outputs.size} more in Download/FileConverter", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ItemState.FAILED -> {
                    val error = item.error
                    Text(error?.message ?: "Conversion failed.", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (error?.kind) {
                            ErrorKind.MISSING_API_KEY, ErrorKind.INVALID_API_KEY, ErrorKind.QUOTA_EXCEEDED ->
                                OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
                            ErrorKind.NO_NETWORK -> OutlinedButton(onClick = onRetry) { Text("Retry") }
                            else -> Unit
                        }
                        if (error?.details != null) {
                            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Details") }
                        }
                    }
                    if (showDetails && error?.details != null) {
                        Text(error.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                ItemState.CANCELLED -> Text("Cancelled", style = MaterialTheme.typography.bodySmall)
                ItemState.QUEUED, ItemState.RUNNING -> Text("Still converting…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

- [ ] **Step 6: Replace the nav host with the final version**

`app/src/main/java/dev/armen/fileconverter/ui/AppNavHost.kt` (replace the whole file; this removes the placeholders):
```kotlin
package dev.armen.fileconverter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.armen.fileconverter.di.AppContainer
import dev.armen.fileconverter.ui.batch.BatchViewModel
import dev.armen.fileconverter.ui.batch.ProgressScreen
import dev.armen.fileconverter.ui.batch.ResultScreen
import dev.armen.fileconverter.ui.convert.ConvertScreen
import dev.armen.fileconverter.ui.convert.ConvertViewModel
import dev.armen.fileconverter.ui.home.HomeScreen
import dev.armen.fileconverter.ui.home.HomeViewModel
import dev.armen.fileconverter.ui.settings.SettingsScreen
import dev.armen.fileconverter.ui.settings.SettingsViewModel

/** [shareSignal] increases each time files are shared into the app; it opens the Convert screen. */
@Composable
fun AppNavHost(container: AppContainer, shareSignal: Int) {
    val navController = rememberNavController()
    LaunchedEffect(shareSignal) {
        if (shareSignal > 0) navController.navigate(Routes.CONVERT) { launchSingleTop = true }
    }
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val viewModel = viewModel { HomeViewModel(container.history) }
            HomeScreen(
                viewModel = viewModel,
                onFilesPicked = { uris ->
                    container.selection.set(uris.mapNotNull { runCatching { container.inputResolver.resolve(it) }.getOrNull() })
                    navController.navigate(Routes.CONVERT)
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            val viewModel = viewModel { SettingsViewModel(container.settings, container::cloudClient, container.history) }
            SettingsScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.CONVERT) {
            val viewModel = viewModel {
                ConvertViewModel(container.selection, container.settings, container.inputResolver, container.scheduler, container.queueDir())
            }
            ConvertScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onBatchStarted = { tag -> navController.navigate(Routes.progress(tag)) },
            )
        }
        val batchArguments = listOf(navArgument(Routes.BATCH_ARG) { type = NavType.StringType })
        composable(Routes.PROGRESS, arguments = batchArguments) { entry ->
            val tag = entry.arguments?.getString(Routes.BATCH_ARG).orEmpty()
            val viewModel = viewModel(key = "progress-$tag") { BatchViewModel(tag, container.scheduler, container.queueDir()) }
            ProgressScreen(
                viewModel = viewModel,
                onFinished = {
                    navController.navigate(Routes.result(tag)) { popUpTo(Routes.PROGRESS) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.RESULT, arguments = batchArguments) { entry ->
            val tag = entry.arguments?.getString(Routes.BATCH_ARG).orEmpty()
            val viewModel = viewModel(key = "result-$tag") { BatchViewModel(tag, container.scheduler, container.queueDir()) }
            ResultScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onRetry = { navController.popBackStack(Routes.CONVERT, inclusive = false) },
                onDone = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
    }
}
```

- [ ] **Step 7: Run all JVM tests and install**

Run: `./gradlew testDebugUnitTest installDebug`
Expected: PASS and installed. In the app: convert `people.csv` (from Task 15) to XLSX → the Progress screen shows the item, then the Result screen shows `people.xlsx` with Open / Share, and Home lists it under Recent conversions.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/armen/fileconverter/ui app/src/test/java/dev/armen/fileconverter/ui
git commit -m "feat: add progress and result screens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 17: README and end-to-end verification

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: the finished app.
- Produces: `README.md`; a verified debug and release build.

- [ ] **Step 1: Write the README**

`README.md`:
````markdown
# File Converter (Android)

Convert images, audio, video and documents on your phone.

- **On the phone, offline:** images (JPG, PNG, WEBP, BMP, TIFF, ICO, GIF, HEIC/AVIF input, images → PDF),
  audio (MP3, M4A, AAC, WAV, FLAC, OGG, OPUS, AIFF, WMA), video (MP4, MKV, WEBM, MOV, AVI, 3GP, video → GIF,
  video → audio), PDF → PNG/JPG pages, TXT → PDF, XLSX ↔ CSV, DOCX ↔ TXT, and a text-only DOCX → PDF.
- **Through CloudConvert (needs your API key and internet):** DOCX, DOC, ODT, RTF, TXT, HTML, MD, XLSX, XLS,
  ODS, CSV, PPTX, PPT, ODP, EPUB, MOBI, AZW3 and PDF, within each document group. DOCX → PDF uses CloudConvert
  when a key is set, so the layout is kept.

Results are saved to `Download/FileConverter/`. You can also share files into the app from any other app.

## Build

Requirements: JDK 21, Android SDK with platform 36. Use a short project path on Windows (long paths break the build).

```bash
./gradlew testDebugUnitTest      # JVM tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest   # device/emulator tests (arm64-v8a or x86_64)
```

## CloudConvert key

Create a key at https://cloudconvert.com/dashboard/api/v2/keys (scopes: `task.read`, `task.write`, `user.read`),
then paste it in **Settings → CloudConvert** and tap **Test**. The key is encrypted with an Android Keystore key
and never leaves the phone except in requests to CloudConvert. Free accounts have a daily conversion limit.

## How it works

`FormatRegistry` picks an engine for each (source, target) pair: Android image APIs, FFmpeg
(`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl`), `PdfDocument`/`PdfRenderer`, small built-in OOXML/CSV
readers and writers, or CloudConvert. Conversions run one at a time in a foreground WorkManager worker,
so long videos keep converting when you leave the app; cancel from the app or the notification.

## Licence note

The FFmpeg build used here is GPL-3.0. That is fine for personal use; check the licence before distributing the app.
````

- [ ] **Step 2: Run the full automated suite and both builds**

Start the emulator (Global Constraints), then run:
```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug assembleRelease lintDebug
```
Expected: every test passes; both APKs build; lint reports no errors (warnings are acceptable). Fix any lint error at its source before continuing.

- [ ] **Step 3: Create sample files on the emulator**

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell screencap -p /sdcard/Download/screen.png
"$ADB" shell screenrecord --time-limit 3 /sdcard/Download/clip.mp4
"$ADB" shell "printf 'Hello from the smoke test\nSecond line\tafter a tab\n' > /sdcard/Download/notes.txt"
"$ADB" shell "printf 'name;city\nAnn;Zürich\n' > /sdcard/Download/people.csv"
```

- [ ] **Step 4: Smoke-test every flow in the app**

Drive the emulator (tap with `"$ADB" shell input tap X Y`, capture with `"$ADB" exec-out screencap -p > shot.png` and look at the screenshot). For each flow, confirm the Result screen and open the output once:

| # | Pick | Convert to | Expected |
|---|---|---|---|
| 1 | `screen.png` | JPG, quality 80, size 1080 px | Done; opens as an image ≤1080 px |
| 2 | `screen.png` + `notes.txt` together | — | "Pick files of one kind at a time…" |
| 3 | `clip.mp4` | GIF | Done; animated GIF of ≤3 s |
| 4 | `clip.mp4` | MP3 | Failed: "Conversion failed." → Details shows "This video has no audio track." (screenrecord has no audio) |
| 5 | `clip.mp4` | WEBM, 480p | Done |
| 6 | `people.csv` | XLSX, then the new `people.xlsx` → CSV | Both done; CSV shows `Zürich` |
| 7 | `notes.txt` | DOCX, then the new `notes.docx` → PDF (no key saved) | Badge "Offline · basic (text only)…"; Result note "Offline basic conversion…" |
| 8 | Settings → key `invalid-key` → Test | — | "Your CloudConvert key was rejected. Check it in Settings." |
| 9 | `notes.docx` | ODT (with the invalid key) | Failed with **Open Settings** button |
| 10 | Share into the app (below) | — | App opens on Convert with the shared file |

For flow 10:
```bash
ID=$("$ADB" shell "content query --uri content://media/external/downloads --projection _id --where \"_display_name='people.xlsx'\"" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)
"$ADB" shell am start -a android.intent.action.SEND -t application/vnd.openxmlformats-officedocument.spreadsheetml.sheet --eu android.intent.extra.STREAM "content://media/external/downloads/$ID" -n dev.armen.fileconverter/.MainActivity
```

Finally remove the invalid key in Settings and clear history. Note any flow that does not behave as listed, fix it with a test that reproduces it, and re-run Step 2.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: add README with formats, build and CloudConvert setup

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
