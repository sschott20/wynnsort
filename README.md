# WynnSort

A Fabric mod that adds quality-based sorting and visual overlays to the trade market in [Wynncraft](https://wynncraft.com), built on top of [Wynntils](https://github.com/Wynntils/Wynntils).

## Features

- **Quality Overlays** — Color-coded backgrounds on trade market items based on overall roll percentage (red/orange/yellow/green/cyan tiers)
- **Percentage Text** — Displays the exact overall roll % on each item
- **Sort by Quality** — Toggle button and keybind (K) to sort trade market results by overall stat quality
- **Configurable** — Enable/disable overlays, percentage text, and the sort button independently

## Requirements

- Minecraft 1.21.4
- Fabric Loader 0.18.0+
- Fabric API 0.119.4+
- Wynntils 3.4.5 (install separately)

## Installation

1. Install Fabric and Fabric API for Minecraft 1.21.4
2. Install [Wynntils](https://github.com/Wynntils/Wynntils/releases) 3.4.5
3. Drop `wynnsort-1.0.0.jar` into your `mods/` folder

## Building from Source

```bash
# Requires Java 21
# Place wynntils-3.4.5-fabric+MC-1.21.4.jar in libs/
./gradlew build
# Output: build/libs/wynnsort-1.0.0.jar
```

## Configuration

Config file: `.minecraft/config/wynnsort.json`

| Option | Default | Description |
|--------|---------|-------------|
| `overlayEnabled` | `true` | Show color-coded quality backgrounds |
| `showPercentageText` | `true` | Display roll % text on items |
| `sortButtonEnabled` | `true` | Add "Sort: Quality" button to trade market |

## License

All rights reserved.
