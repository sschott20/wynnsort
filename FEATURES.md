# WynnSort Features

## Quality Overlay
**Toggle:** J key | **Config:** Quality Overlay section

Colors every gear item slot based on its roll quality: red (0-29%), orange (30-59%), yellow (60-79%), green (80-94%), cyan (95-100%). Shows the percentage number in the corner and gold/silver/bronze rank badges (#1, #2, #3) on the top items. Works in any container, not just the trade market.

## Best Item Highlight
**Toggle:** same as Quality Overlay

Draws a gold border around the single highest-scoring item in the container so you can spot it instantly.

## Tooltip Score
**Toggle:** same as Quality Overlay

Injects a color-coded "WynnSort: XX%" line into gear tooltips showing the item's quality score and current scoring mode (overall, filtered, or stat name).

## Trade Market Sort Button
**Config:** Sort Button

Adds a "Sort/Sorting" toggle button to the trade market search results screen. When active, appends a `sort:overall` (or `sort:<stat>`) token to the search query so Wynntils sorts results by quality. A small text box lets you type a specific stat name to sort by.

## Stat Filter & Presets
**Config:** Search Presets | **Keybind:** P to cycle presets

On any container screen, a stat filter text box appears to the right. Type a stat name (e.g. `walkSpeed`), a comparison (`walkSpeed > 50`), or comma-separated filters. Per-stat weight boxes auto-appear for stats found in the container. Up to 5 named presets can be saved (left-click empty slot to name, left-click filled slot to apply, right-click to overwrite). Press P to cycle through presets.

## Auto-Sort Cheapest
**Config:** Auto-Sort Cheapest

Automatically sorts trade market results by lowest price.

## Scale Mode
**Config:** Weighted Scale (Nori/Wynnpool)

Toggle between Nori/Wynnpool community-weighted scoring and straight Wynntils overall percentage. Buttons labeled "Nori" / "Overall" appear next to the stat filter.

## Market Price Cache
**Config:** Market & Pricing section

Automatically records the cheapest listing price for every item you browse on the trade market. Injects into tooltips: cached price with age ("Market: 2.5le (3h ago)"), trend arrow, price range, and local crowdsource average. Prices older than 7 days (configurable) are hidden.

## Price History
**Config:** Price History Tracking

Stores up to 100 price observations per item over 30 days (configurable). Computes min/max/avg/median and trend (rising/falling/stable) shown in tooltips.

## Crowdsource Data
**Config:** Crowdsource Data section

Collects market listing data (item, price, rarity, type) and stores it locally. Optionally submits batches to a remote API for community aggregation. Shows community average in tooltips if a remote API is configured.

## Trade History
**Keybind:** H | **Config:** Trade History & Logging section

Automatically captures every buy/sell transaction on the trade market. Press H to open the history screen with: sortable columns (date/name/price), type filter (all/buy/sell), text search, min price filter, and a "Group" mode that pairs buys with matching sells to show per-item profit. Bottom bar shows total spent, earned, and net profit/loss.

## Sell-Screen Buy Price Overlay
**Always active** (when trade history is enabled)

When selling an item you previously bought, a green "Bought for: X" banner appears at the top of the screen (with buy tax applied if configured), so you know your break-even price.

## Lootrun Beacon HUD
**Config:** Lootrun Beacon HUD

During lootruns, shows a left-side HUD panel tracking all 12 beacon types with counts and remaining challenge durations. Handles rainbow/vibrant mechanics, aqua boost stacking, and persists state across relogs (within 30 minutes).

## Lootrun Session Stats
**Config:** Lootrun Stats HUD

Right-side HUD panel showing real-time lootrun stats: challenges completed, pulls, rerolls, sacrifices, beacon summary, and elapsed time. Shows "Run Complete!" or "Run Failed" for 10 seconds after a run ends.

## Lootrun History
**Keybind:** L | **Config:** Lootrun History

Records every completed/failed lootrun. Press L to open a history screen with lifetime stats (total runs, completion rate, avg pulls/challenges, total XP) and a scrollable table of individual runs.

## Dry Streak Tracker
**Config:** Dry Streak Tracker

Tracks pulls without a mythic drop across sessions. Bottom-right HUD during lootruns shows: current streak (color-coded at 500/1000/2000 thresholds), lifetime pulls and mythic count, longest streak ever, and last mythic found with time-ago.

## Diagnostics
**Keybind:** F8 | **Config:** Diagnostic Logging

Structured event log viewer. Shows timestamped, color-coded events from all features. Filter by category (Trade/Lootrun/Beacon/Error). Export button writes to file.

## Settings Screen
**Keybind:** ; (semicolon) | Also accessible via ModMenu

All toggles in one scrollable screen organized into sections: Quality Overlay, Trade Market, Lootrun, Market & Pricing, Crowdsource Data, Trade History & Logging.

---

## Keybinds

| Key | Action |
|-----|--------|
| **J** | Toggle quality overlay on/off |
| **H** | Open trade history |
| **;** | Open settings |
| **L** | Open lootrun history |
| **F8** | Open diagnostics |
| **P** | Cycle search presets (in container) |

All keybinds are rebindable in Minecraft's Controls menu under the "WynnSort" category.
