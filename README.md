# AudioPipe

AudioPipe captures audio from one Android app and plays it through a selected output device. It is designed for setups where Android Auto should keep navigation and calls on the car audio system while music is routed to a separate Bluetooth receiver or headphones.

The current alpha combines the Shizuku-first backend, media-button forwarding, automatic Bluetooth route recovery, fast app selection, and car-mode power optimizations.

## Download

The latest tested build is **AudioPipe 1.1.0-alpha.2**:

- [Release notes](https://github.com/nikonovita/AudioPipe/releases/tag/v1.1.0-alpha.2)
- [Download the APK](https://github.com/nikonovita/AudioPipe/releases/download/v1.1.0-alpha.2/AudioPipe-1.1.0-alpha.2.apk)

The APK is a debug-signed alpha build intended for testing. Builds produced by this repository use the same project debug key, so they can update one another.

## Features

- **Per-app audio capture** — route music from one selected app without changing the system-wide output.
- **Shizuku-first backend** — privileged `appops` commands continue working when wireless Android Auto occupies the phone's Wi-Fi connection.
- **ADB fallback** — self-to-self wireless ADB remains available when Shizuku is unavailable.
- **Bluetooth route recovery** — remembers the chosen output by device type and name, monitors `AudioDeviceCallback`, and reapplies `AudioTrack.setPreferredDevice()` after Android Auto rebuilds the route.
- **Disconnect fallback** — provides a Bluetooth settings button in the app and notification when Android fully disconnects the selected A2DP device.
- **App search** — filters installed apps by display name or package name, including system apps.
- **Quick selection** — dedicated buttons for YouTube Music, YouTube, and Spotify.
- **Media-button bridge** — forwards play, pause, previous, and next commands to the selected app when Notification Access is enabled.
- **Volume and balance controls** — adjusts the routed stream without changing the selected source app.
- **Car-mode efficiency** — uses blocking capture reads, media-only capture, valid-byte forwarding, allocation-free buffer fan-out, and lazy audio effects.

## Requirements

- Android 11 or newer (`minSdk 30`).
- Shizuku installed and running is strongly recommended.
- Wireless debugging enabled when required to start Shizuku or use the ADB fallback.
- Screen-capture permission each time an AudioPipe routing session starts.
- Notification Access for direct media-button forwarding. Without it, AudioPipe falls back to shell media-key events.

## Recommended setup

1. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/).
2. Install the AudioPipe alpha APK.
3. Open AudioPipe and grant Shizuku access when prompted.
4. Grant microphone/screen-capture permissions required by Android playback capture.
5. Optionally enable **Notification Access** for `AudioPipe Media Bridge` to improve media-button forwarding.
6. Find the source app using search or a quick-select button.
7. Choose the preferred Bluetooth, wired, USB, or built-in output.
8. Tap **Start** and approve Android's screen-capture dialog.

When Android Auto changes the Bluetooth route, AudioPipe retries the preferred output immediately and again after short delays. If the device reappears as an available A2DP output, routing is restored automatically.

## Bluetooth limitation

Android does not expose a public API that lets an ordinary third-party app force-connect a disconnected A2DP profile. AudioPipe can automatically reselect BlueMusic/SWL-BT, Sony WH-1000XM3, or another saved output as soon as Android reports it as available. If Android Auto completely disconnects the profile, reconnect it through the Bluetooth settings shortcut; AudioPipe will restore its route after the device returns.

No root-only or hidden Bluetooth APIs are used.

## How it works

```text
Source app
   │
   │ Android Playback Capture
   ▼
AudioRecord ──► PlayBackThread ──► AudioTrack
                                      │
                                      │ preferredDevice
                                      ▼
                         Selected Bluetooth / wired output

Shizuku (preferred) or ADB (fallback)
   └── controls PLAY_AUDIO app-op for the selected source app
```

The foreground service watches audio-device changes. Device IDs are not persisted because Android can assign a different ID after reconnecting. AudioPipe instead matches the saved device type and normalized product name, then reapplies the current route without restarting the capture session.

## Building

The project requires JDK 17 and Android SDK 34.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The generated APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the unit tests, builds the APK, and uploads it as an artifact for pushes to the active development/release branches.

## Current alpha limitations

- Android displays a MediaProjection consent dialog for every new routing session.
- Some apps prohibit playback capture through their own Android audio policy.
- A fully disconnected Bluetooth profile cannot be force-connected without unsupported privileged or hidden APIs.
- Manufacturer-specific Android audio routing may behave differently; Pixel/stock Android is the primary target.
- This is an alpha/debug build and may contain incomplete UI polish or device-specific issues.

## Branches

- `main` — upstream baseline.
- `media-button-bridge` — Shizuku, media controls, app search, and Bluetooth recovery development.
- `car-audio-optimized` — original energy-optimization work.
- `alpha-release` — combined alpha release candidate.

## License

This project is provided as-is for educational and experimental use. See [LICENSE.txt](LICENSE.txt).
