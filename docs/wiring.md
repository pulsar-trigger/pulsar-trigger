# Wiring Guide

## Pin Map

| ESP32 GPIO | Function         | Direction   | Module / Component             |
|------------|------------------|-------------|--------------------------------|
| GPIO 2     | Status LED       | Output      | On-board LED (built into most ESP32 dev boards) |
| GPIO 25    | Shutter trigger  | Output      | Optocoupler → camera remote shutter |
| GPIO 26    | Focus trigger    | Output      | Optocoupler → camera remote focus |
| GPIO 33    | Battery voltage  | Analog In   | Resistor voltage divider       |

> GPIOs 34 and 35 are input-only on the ESP32 (no internal pull-up/pull-down).

## Camera Shutter & Focus (GPIO 25, 26)

Most cameras with a 2.5 mm or N3 remote port expose three lines: **ground**, **focus**, and **shutter**. Shorting focus-to-ground half-presses; shorting shutter-to-ground fires.

Use one optocoupler per line to isolate the ESP32 from the camera:

```
ESP32 GPIO 25 ──[330Ω]──► Optocoupler LED anode
                           Optocoupler LED cathode ──► GND

                           Optocoupler collector ──► Camera SHUTTER line
                           Optocoupler emitter   ──► Camera GND line

ESP32 GPIO 26 ──[330Ω]──► Optocoupler LED anode
                           Optocoupler LED cathode ──► GND

                           Optocoupler collector ──► Camera FOCUS line
                           Optocoupler emitter   ──► Camera GND line
```

Suitable optocouplers: PC817, 4N35, TLP281. A 330 Ω resistor limits LED current to ~10 mA at 3.3 V.

## Battery Monitoring (GPIO 33)

To read a single-cell LiPo (3.2–4.2 V) with the ESP32's 3.3 V ADC, use a resistive voltage divider:

```
Battery +  ──[100kΩ]──┬──[100kΩ]──► GND
                       │
                       └──► ESP32 GPIO 33
```

Two equal resistors give a 2:1 ratio (`BATTERY_DIVIDER_RATIO = 2.0`), mapping 4.2 V → 2.1 V which is within ADC range. The firmware reports battery percentage based on:

- Full: 4200 mV
- Empty: 3200 mV

## Power Supply

- Power the ESP32 via USB during development
- For portable use: single-cell 3.7 V LiPo → ESP32 VIN or a boost/buck module to USB

## Summary Wiring Diagram

```
                        ┌───────────────────┐
                        │     ESP32 Dev      │
                        │                    │
     Camera Shutter ◄───┤ GPIO 25    GPIO 2  ├──► On-board LED
     (via optocoupler)  │                    │
                        │                    │
     Camera Focus   ◄───┤ GPIO 26            │
     (via optocoupler)  │                    │
                        │                    │
     Battery divider ──►┤ GPIO 33            │
                        │                    │
                        │        GND   3.3V  │
                        └──────┬───────┬─────┘
                               │       │
                            common   common
                             GND     VCC
```
