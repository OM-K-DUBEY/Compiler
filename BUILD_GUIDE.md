# Build & Verification Guide — DevForge AI

## 1. Prerequisites
- **JDK**: JDK 17 or newer (tested with OpenJDK 21 / Java 11 bytecode target).
- **Android SDK**: `compileSdk = 36` (minorApiLevel 1), `minSdk = 24`, `targetSdk = 36`.
- **Gradle / AGP**: Gradle `9.3.1`, Android Gradle Plugin `9.1.1`, Kotlin `2.4.21`.
- Environment variable `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) configured. Do **not** create `local.properties` in this build environment.

## 2. Building APKs
- **Debug APK** (signed with project `debugConfig`):
  ```bash
  gradle :app:assembleDebug
  ```
  Output: `app/build/outputs/apk/debug/app-debug.apk`

- **Release APK** (requires upload keystore + `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` environment variables):
  ```bash
  gradle :app:assembleRelease
  ```
  Output: `app/build/outputs/apk/release/app-release.apk`

- **Release App Bundle (AAB)**:
  ```bash
  gradle :app:bundleRelease
  ```

## 3. Running Automated Tests
- Run the full JVM unit & Robolectric test suite:
  ```bash
  gradle :app:testDebugUnitTest --console=plain
  ```
- Note: Use `gradle` directly in this environment; `./gradlew` is not supported.

## 4. On-Device Compilation & Native Toolchains on Android
- **Built-in on-device support**: Web static packaging (`HTML`/`CSS`/`JS`), Android `WebView` live runtime preview, `/system/bin/sh` interactive shell, static syntax/manifest validation across all languages, JS/Web minification, AES-256-GCM encryption/vaulting, and archive leak checks.
- **External CLI toolchains (`gradle`, `flutter`, `npm`, `cmake`, `javac`, `kotlinc`, `llvm-strip`, `pyarmor`, `nuitka`)**: Standard unrooted Android 10+ enforces SELinux `W^X` (`noexec` on app-private storage). DevForge AI probes `PATH` and the user-configured Custom Toolchain Path (e.g., Termux/chroot) and reports `UNAVAILABLE` honestly when no executable binary is present.
