# WynnSort

A Fabric companion mod for [Wynntils](https://github.com/Wynntils/Wynntils) that adds quality-based sorting, lootrun tracking, trade market analytics, and more to [Wynncraft](https://wynncraft.com).

## Features

### Trade Market

- **Quality Overlays** — Color-coded slot backgrounds based on gear roll quality (red → orange → yellow → green → cyan). Toggle with **J**.
- **Percentage Text** — Exact overall roll % displayed on each item slot.
- **Sort by Quality** — Toggle button to sort trade market results by overall stat quality or a specific stat.
- **Stat Filtering** — Multi-stat filter UI on container screens with dynamic row editors. Supports Nori/Wynnpool weighted scoring.
- **Best Item Highlight** — Gold border around the highest-scoring item in the current view.
- **Tooltip Score** — Adds a quality score line to gear tooltips when hovering.
- **Search Presets** — Save up to 10 custom search queries with quick-apply buttons. Cycle with **P**.

### Trade Market Analytics

- **Transaction History** — Logs all buys and sells with prices, timestamps, and item details. Browsable screen with search, date filtering, and price stats. Open with **H**.
- **Price Cache** — Caches observed item prices and shows them on tooltips with freshness indicators.
- **Price History** — Time-series price tracking per item (up to 30 days) for trend analysis.
- **Sell Screen Helper** — Shows "Bought for: X" when selling a previously purchased item.
- **Crowdsource Collection** — Optionally collects anonymized price observations for community data.

### Lootrun Tracking

- **Beacon Tracker HUD** — Real-time display of all 12 beacon types during lootruns. Shows duration countdowns, pull/curse counts, vibrant status, and aqua multipliers.
- **Session Stats HUD** — Live session panel showing challenges, pulls, rerolls, sacrifices, beacon summary, and duration. Shows final summary for 10 seconds after completion.
- **Lootrun History** — Persistent record of every lootrun with lifetime aggregate stats. Browsable screen with per-run details. Open with **L**.
- **Mythic Dry Streak** — Tracks pulls and individual items seen since last mythic drop. Sends stats to local chat after each lootrun with current streak, lifetime totals, historical averages per mythic, and probability context (based on ~1/2500 mythic rate).

### Utility

- **Config Screen** — In-game settings screen with all toggles organized by category. Open with **;** or via Mod Menu.
- **Diagnostic Viewer** — Structured event log with category filtering and export. Open with **F8**.

## Keybinds

| Key | Action |
|-----|--------|
| **J** | Toggle quality overlay |
| **H** | Transaction history |
| **L** | Lootrun history |
| **P** | Cycle search presets |
| **;** | Config screen |
| **F8** | Diagnostic viewer |

## Requirements

- Minecraft 1.21.4
- Fabric Loader 0.18.0+
- Fabric API 0.119.4+
- [Wynntils](https://github.com/Wynntils/Wynntils/releases) 3.4.5 (install separately)

## Installation

1. Install Fabric and Fabric API for Minecraft 1.21.4
2. Install Wynntils 3.4.5
3. Drop `wynnsort-1.0.0.jar` into your `mods/` folder

## Building from Source

```bash
# Requires Java 21
# Place wynntils-3.4.5-fabric+MC-1.21.4.jar in libs/
./gradlew build
# Output: build/libs/wynnsort-1.0.0.jar
```

## Configuration

All settings are accessible in-game via the config screen (**;** key) or Mod Menu.

Config file: `.minecraft/config/wynnsort.json`

Persistent data is stored in `.minecraft/config/wynnsort/` (transaction history, lootrun records, price history, dry streak stats).

## License

All rights reserved.
