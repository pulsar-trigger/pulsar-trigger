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

## Getting Started

### Firmware
```bash
cd firmware
pio run                  # build
pio run -t upload        # flash
pio device monitor       # serial console
```

### Android
Open `android/` in Android Studio. Build and deploy to a device with BLE support (API 26+).

## BLE Protocol

See [docs/ble-protocol.md](docs/ble-protocol.md) for the full packet specification.

## License

MIT
