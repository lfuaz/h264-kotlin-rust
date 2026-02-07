# H264 Viewer - Client & Server

This repository contains both the Android client and Rust server for the H264 Viewer application.

## Project Structure

```
.
├── client-h264/      # Android Kotlin client
│   ├── app/
│   ├── gradle/
│   ├── build.gradle.kts
│   └── ...
├── server-h264/      # Rust backend server
│   ├── src/
│   ├── Cargo.toml
│   └── ...
```

## Requirements (Windows)

### Prerequisites
- **Java Development Kit (JDK)**: Version 11 or higher
- **Android SDK**: Version 34 (handled by Android Studio)
- **Rust**: 1.70+ (for server builds)

### Verify Installation
```bash
java -version
rustc --version
cargo --version
```

## Getting Started

### Client (Android)
```bash
cd client-h264
./gradlew build
```

### Server (Rust)
```bash
cd server-h264
cargo build --release
```

## Development Workflow

### Android Client

**Build debug APK:**
```bash
cd client-h264
.\gradlew.bat assembleDebug
```

**Install on device/emulator (Windows):**
```bash
adb -s [YOUR_IP:ADB_PORT] install -r app\build\outputs\apk\debug\app-debug.apk
```

Replace [YOUR_IP:ADB_PORT] with your device IP or emulator ID.

**List connected devices:**
```bash
adb devices
```

### Rust Server

**Build and run:**
```bash
cd server-h264
cargo run --release
```

## Development

- **Client**: Android Studio with Kotlin
- **Server**: Rust with Cargo
