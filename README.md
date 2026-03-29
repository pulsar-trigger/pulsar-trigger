# Pulsar

An open-source camera intervalometer and trigger system, inspired by MIOPS Trigger.

**Android app** (BLE client) ↔ **ESP32 firmware** (BLE server + camera control)

## Trigger Modes

| Mode | Description |
|------|-------------|
| **Intervalometer** | Time-lapse with configurable interval, exposure, shot count, and start delay |
| **Sound** | Fires shutter when sound exceeds threshold (e.g. balloon pop, clap) |
| **Lightning** | Fires on sudden light change (photodiode sensor) |
| **Laser** | Fires when a laser beam is broken (break-beam sensor) |
| **HDR** | Automatic exposure bracketing (up to 5 exposures) |
| **Press & Hold** | Shutter open while START is held |
| **Press & Lock** | Toggle shutter open/closed |

## Repository Structure

```
pulsar/
├── firmware/         ← ESP32 PlatformIO project
│   ├── platformio.ini
│   ├── include/      ← headers (config, protocol, camera, triggers, BLE)
│   └── src/          ← implementation (.cpp)
├── android/          ← Android Studio / Gradle project
│   └── app/src/main/java/com/ehrocha/pulsar/
│       ├── ble/       ← Protocol, CommandBuilder, BLE manager
│       ├── viewmodel/ ← PulsarViewModel
│       └── ui/        ← Compose screens (Scan, Control)
└── docs/
    └── ble-protocol.md  ← BLE packet format specification
```

## Hardware

- **ESP32** dev board (any with BLE)
- 2× optocouplers (shutter + focus → camera remote port)
- Electret mic / sound sensor module (analog)
- Photodiode module (analog, for lightning)
- Laser + photoresistor (break-beam)
- LiPo battery + voltage divider on GPIO 33

## Building

### Firmware

**Prerequisites:** [PlatformIO CLI](https://docs.platformio.org/en/latest/core/installation.html)

```bash
cd firmware
pio run                  # build
pio run -t upload        # flash to connected ESP32
pio device monitor       # serial console (115200 baud)
```

The build produces `firmware/.pio/build/esp32dev/firmware.bin` (~1.1 MB).

### Android

**Prerequisites:**
- JDK 17 (e.g. [Eclipse Temurin](https://adoptium.net/))
- Android SDK with platform 35 and build-tools 35.0.0

**SDK setup** (if not using Android Studio):
```bash
# Download Android command-line tools from https://developer.android.com/studio#command-line-tools-only
# Then install required SDK components:
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

**Configure `android/local.properties`** (not tracked in git):
```properties
sdk.dir=/path/to/your/Android/Sdk
```

JDK 17 is pinned in `gradle.properties` via `org.gradle.java.home`. Update the path if your JDK 17 installation differs.

**Build:**
```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

To build and install on a connected device:
```bash
./gradlew installDebug
```

**Using Android Studio:** Open the `android/` directory as a project. Studio will detect the Gradle wrapper and SDK configuration automatically.

## Wiring

See [docs/wiring.md](docs/wiring.md) for the full wiring guide with pin assignments, optocoupler circuits, sensor hookups, and battery monitoring.

## BLE Protocol

See [docs/ble-protocol.md](docs/ble-protocol.md) for the full packet specification.

## License

GNU General Public License v3.0 or later (GPL-3.0-or-later).

See [LICENSE](LICENSE) for the full text.
