# carBuddy

An experiment: run ordinary phone apps on the Android Auto car screen.

carBuddy is a sideloaded Android Auto app. It does **not** screen-capture the
phone. It creates a **separate virtual display** at the car screen's size and
shape, and launches apps onto it. The car shows that display; the phone's own
screen stays free.

```
Android Auto ──gives a navigation app──► a raw Surface covering the car screen
                                              ▲
carBuddy (Shizuku, shell privileges) ── VirtualDisplay renders straight into it
                                              ▲
                     apps launched with `am start --display <id>`
```

## How it works

| Piece | File | Runs as |
|---|---|---|
| Android Auto entry point, car screens | `CarBuddyService.kt` | the app |
| Surface ↔ virtual display link, touch forwarding | `CarDisplay.kt` | the app |
| Creating the display, launching apps, injecting input | `DisplayService.kt` | **shell** (uid 2000), via Shizuku |
| Shizuku connection | `ShizukuBridge.kt` | the app |
| Phone setup screen and on-phone preview | `MainActivity.kt` | the app |

- **Why navigation category:** of all Android Auto app categories, only
  navigation apps get a raw `Surface` to draw on (for the map). Everything else is
  fixed templates.
- **Why Shizuku:** a normal app may create a virtual display, but may not launch
  *other* apps onto it or inject input. The shell user (what `adb shell` runs as)
  may. Shizuku runs `DisplayService` as that user without root.
- **Display flags:** on Android 13+ the display is *trusted* (other apps and the
  system's secondary home screen can appear on it), in its *own display group*
  (stays awake when the phone screen is off) and *always unlocked* (the lock screen
  does not cover it). On older versions or if a flag is refused, it falls back to
  plainer flag sets and says so.
- **Touch:** Android Auto reports taps (`onClick`, car API 5+) with a position,
  but drags only as distances (`onScroll`). Taps are injected where they land.
  Drags move a virtual finger that starts at the screen centre — enough to scroll.

## Setup

1. Install **Shizuku** and start it in Wireless debugging mode. It needs
   restarting after each phone reboot.
2. Install carBuddy and open it on the phone. Tap **Grant Shizuku access**.
3. Optional check: **Start preview**, then **Open app…** — a car-shaped display
   appears inside the screen. If this works, the privileged half works.
4. Android Auto → Settings → tap **Version** ~10 times → ⋮ → **Developer
   settings** → turn on **Unknown sources**.
5. Connect to the car, open carBuddy from the launcher. Grid button = pick an app,
   arrow = Back, house = Home on the car display.

## Expected limits

- ~30 fps and 0.1–0.3 s latency: everything still goes through Android Auto's
  video stream to the car.
- DRM video (Netflix etc.) will most likely be black — that needs a *secure*
  display, which only system apps can create.
- An app already open on the phone may come to the front **there** instead of
  moving to the car. Close it on the phone first; the car shows a warning when
  this happens.
- One display at a time: starting the phone preview while the car is connected
  takes the display away from the car.

## Build

No Android Studio needed:

```
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (`local.properties` → `sdk.dir=...`, or
`ANDROID_HOME`). `debug.keystore` is committed on purpose, so a build from any
machine installs over the previous one. It is a throwaway debug key, not a
release key.

Pushes are built by GitHub Actions. On `main`, the APK is published to the
rolling `latest` release.
