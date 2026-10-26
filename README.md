# HeadlessBridge

**Android headless WebView + local HTTP automation bridge**

Package: `com.hb.headlessbridge`  
Min SDK 26 · Target SDK 34 · Kotlin · Jetpack

---

## What it does

- Full-screen WebView that looks like real Chromium (UA stripped of `WV`/`wv`)
- Local HTTP server (default port **8765**) controllable from Termux / same LAN
- Secret key authentication (64-hex, generated once, stored in private SharedPreferences)
- Headless mode: hides WebView, shows grey screen, runs as Foreground Service
- Ad blocking + anti-popup
- Cookies, JS eval, navigate, click, type, scroll, screenshot, curl proxy
- Wireless debugging helper + WebView DevTools support

---

## Project structure

```
HeadlessBridge/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/hb/headlessbridge/
│       │   ├── HeadlessBridgeApp.kt
│       │   ├── MainActivity.kt
│       │   ├── server/BridgeHttpServer.kt
│       │   ├── service/BridgeServerService.kt
│       │   ├── service/OverlayService.kt
│       │   ├── webview/WebViewEngine.kt
│       │   ├── util/SecretKeyManager.kt
│       │   ├── util/AdBlocker.kt
│       │   └── receiver/{BootReceiver,KeepAliveReceiver}.kt
│       └── res/...
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## Build instructions

### Prerequisites
- Android Studio Hedgehog / Iguana or newer (AGP 8.2+)
- JDK 17
- Android SDK 34

### Debug APK

```bash
cd HeadlessBridge
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

If you don’t have the Gradle wrapper yet:

```bash
# From Android Studio: open the project → it will generate the wrapper
# Or manually:
gradle wrapper --gradle-version 8.5
```

### Release APK (signed)

1. Create a keystore (once):

```bash
keytool -genkey -v -keystore headlessbridge.jks -keyalg RSA -keysize 2048 -validity 10000 -alias hb
```

2. Add to `app/build.gradle.kts` (or use `signingConfigs`):

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("headlessbridge.jks")
            storePassword = "your-password"
            keyAlias = "hb"
            keyPassword = "your-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}
```

3. Build:

```bash
./gradlew assembleRelease
```

---

## Install & first run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hb.headlessbridge/.MainActivity
```

1. Open the app → Google loads in WebView.
2. Tap the **gear icon** (top-right).
3. Tap **Copy secret key** → key is on clipboard.
4. (Recommended) Tap **Ignore Battery Optimization** and allow.
5. Toggle **Headless Mode** if you want the grey screen + stronger keep-alive.

---

## API usage (from Termux / adb shell / same device)

Base URL: `http://127.0.0.1:8765`

### 1. Get the key (localhost only, no auth)

```bash
curl http://127.0.0.1:8765/key
# → {"ok":true,"data":{"key":"a1b2c3...","port":8765}}
```

### 2. Help page

```bash
curl http://127.0.0.1:8765/help
```

### 3. Ping

```bash
curl http://127.0.0.1:8765/ping
```

### 4. Navigate (requires key)

```bash
KEY=$(curl -s http://127.0.0.1:8765/key | jq -r .data.key)

curl -X POST -H "X-Auth-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://x.com"}' \
  http://127.0.0.1:8765/navigate
```

Or GET shortcut:

```bash
curl -H "X-Auth-Key: $KEY" "http://127.0.0.1:8765/navigate?url=https://example.com"
```

### 5. Evaluate JavaScript

```bash
curl -X POST -H "X-Auth-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"js":"document.title"}' \
  http://127.0.0.1:8765/eval
```

### 6. Cookies

```bash
curl -H "X-Auth-Key: $KEY" http://127.0.0.1:8765/cookies
```

### 7. HTML snapshot

```bash
curl -H "X-Auth-Key: $KEY" http://127.0.0.1:8765/html | jq -r .data.html | head
```

### 8. Screenshot (base64 PNG)

```bash
curl -X POST -H "X-Auth-Key: $KEY" http://127.0.0.1:8765/screenshot | jq -r .data.png_base64 > shot.b64
base64 -d shot.b64 > shot.png
```

### 9. Click / Type / Scroll

```bash
curl -X POST -H "X-Auth-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"selector":"#search"}' http://127.0.0.1:8765/click

curl -X POST -H "X-Auth-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"selector":"input[name=q]","text":"hello"}' http://127.0.0.1:8765/type

curl -X POST -H "X-Auth-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"x":0,"y":500}' http://127.0.0.1:8765/scroll
```

### 10. Toggle headless from API

```bash
curl -X POST -H "X-Auth-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"on":true}' http://127.0.0.1:8765/headless
```

### 11. Health

```bash
curl -H "X-Auth-Key: $KEY" http://127.0.0.1:8765/health
```

---

## Wireless Debugging (step-by-step)

1. On the phone: **Settings → Developer options → Wireless debugging → ON**
2. Tap **Pair device with pairing code** → note the **pairing code** and **port** (usually 37xxx).
3. In HeadlessBridge → Settings → **Wireless Debug** → enter code + port.
4. If the app cannot run `adb` itself (normal on non-rooted devices), the command is copied to clipboard.
5. In Termux (or another machine that can reach the phone):

```bash
adb pair 127.0.0.1:<pairPort> <code>
# after success:
adb connect 127.0.0.1:<debugPort>
```

6. WebView debugging is already enabled → open Chrome on PC → `chrome://inspect` → you should see the WebView.

---

## Security notes

- Secret key is 64 hex characters, generated with `SecureRandom`, stored only in `MODE_PRIVATE` SharedPreferences.
- Every endpoint except `/help`, `/ping`, `/key` requires `X-Auth-Key` header or `?key=` query.
- `/key` is restricted to localhost (`127.0.0.1` / `::1`).
- Rate limit: 60 requests per minute per IP.
- Never log the full key.

---

## Keep-alive features

- Foreground Service (`dataSync`) + persistent notification
- Partial wake lock
- `START_STICKY`
- Optional battery-optimization exemption
- Boot receiver (disabled by default – enable via prefs if you add a toggle)
- Alarm / WorkManager can be wired to `KeepAliveReceiver` for extra resilience

---

## Known limitations & tips

- Screenshot uses `WebView.draw(Canvas)` – works best when the view has non-zero size. In pure headless the last rendered size is used.
- Changing port requires restarting the service (kill app or use `/stop` then relaunch).
- For true background automation, keep Headless ON and grant battery exemption.
- Some sites detect headless via other signals; the UA strip helps a lot but is not 100 % stealth.
- Android System WebView should be reasonably recent (≥ Chromium 100 recommended).

---

## License

For personal / educational use. Use responsibly and only on devices / accounts you own.
