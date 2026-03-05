# ARCHITECTURE.md

Authoritative technical reference for the WynnSort mod codebase. This file is maintained for automated agents and should contain enough detail to understand the entire mod without reading source files.

**Target environment:** Minecraft 1.21.4, Fabric Loader 0.18.0, Fabric API 0.119.4, Wynntils 3.4.5, Java 21, Mojang mappings.

---

## Quick Reference Card

```
Source files:    48 Java files across 7 packages
Mixins:          3 (WynntilsModMixin, TradeMarketScreenMixin, ContainerScreenMixin)
Event listeners: 10 registered on Wynntils event bus
HUD callbacks:   3 registered on Fabric HudRenderCallback
Keybinds:        6 registered via Fabric KeyBindingHelper
Screens:         4 custom screens (Config, TransactionHistory, LootrunHistory, Diagnostic)
Persistent stores: 7 JSON data files + 2 log files
Named IO threads: 4 daemon threads (WynnSort-IO, WynnSort-MarketIO, WynnSort-PriceHistoryIO, WynnSort-CrowdsourceFlush)
Config fields:   22 fields in WynnSortConfig
```

---

## Package Structure

```
com.wynnsort/
  WynnSortMod.java              Fabric ClientModInitializer; entry point, keybind registration, HUD registration
  SortState.java                 Static state: current filter/sort mode (overall, single-stat, multi-filter)
  StatFilter.java                Record: parsed filter condition (e.g. "healthRegen > 90")
  StatPickerHelper.java          Scans container slots for unique stat display names
  TradeMarketSortHelper.java     Lazy-loaded reflective helper to inject sort tokens into Wynntils search widget
  MainScaleHelper.java           Detects mythic items in containers; finds highest base stat
  ModMenuIntegration.java        ModMenu API: provides config screen factory

com.wynnsort.config/
  WynnSortConfig.java            Gson-based JSON config with 22 fields; load/save to .minecraft/config/wynnsort.json

com.wynnsort.feature/
  QualityOverlayFeature.java     Renders colored quality backgrounds + rank badges on container slots
  BestItemHighlightFeature.java  Draws gold border around highest-scoring item in container
  TooltipFeature.java            Injects "WynnSort: XX%" score line into gear tooltips
  MarketPriceFeature.java        Records market prices from trade market; adds price/trend/tax to tooltips
  TradeMarketLogger.java         Comprehensive trade market observer: logging + transaction capture
  LootrunBeaconTracker.java      Tracks all 12 beacon types during lootruns; renders left-side HUD
  LootrunSessionStats.java       Live session stats (challenges, pulls, rerolls); renders right-side HUD
  LootrunSessionData.java        POJO: per-session data (start time, beacon counts, pulls, duration)
  DryStreakTracker.java           Tracks pulls since last mythic drop; renders bottom-right HUD
  LootrunHistoryFeature.java     Records completed/failed lootruns to LootrunStore

com.wynnsort.market/
  CrowdsourceCollector.java      Collects market listings for crowdsource aggregation; scheduled flush
  CrowdsourceClient.java         Local JSON database + optional remote API client for crowdsource data
  CrowdsourceEntry.java          POJO: single market observation (item, price, rarity, type, quality)
  CrowdsourceQueue.java          Thread-safe dedup queue (ConcurrentHashMap-backed set)
  MarketPriceEntry.java          POJO: cached market price (price + timestamp)
  MarketPriceStore.java          ConcurrentHashMap store: item -> cheapest recent price; async 5s save
  PriceHistoryEntry.java         POJO: single price observation (price + timestamp)
  PriceHistoryStore.java         ConcurrentHashMap store: item -> list of price observations; async 10s save
  PriceStats.java                Record: min, max, avg, median, count, trend, latestPrice
  PriceTrend.java                Enum: RISING, FALLING, STABLE, UNKNOWN
  SearchPreset.java              POJO: saved search preset (name, query, sortToken)
  SearchPresetStore.java         CopyOnWriteArrayList store: up to 10 presets; immediate save

com.wynnsort.history/
  TransactionRecord.java         POJO: single buy/sell transaction (name, price, type, fingerprint)
  TransactionStore.java          CopyOnWriteArrayList store: up to 5000 transactions; async 5s save
  TransactionPair.java           POJO: matched buy+sell pair with computed profit

com.wynnsort.lootrun/
  LootrunRecord.java             POJO: single lootrun run record (outcome, stats, beacons)
  LootrunStore.java              CopyOnWriteArrayList store: up to 500 records; async 5s save

com.wynnsort.screen/
  WynnSortConfigScreen.java      Scrollable settings screen with toggle buttons and edit boxes
  TransactionHistoryScreen.java  Trade history screen with sorting, filtering, grouping, profit/loss
  TransactionListWidget.java     AbstractSelectionList widget for transaction entries + paired entries
  LootrunHistoryScreen.java      Lootrun history screen with lifetime stats bar and scrollable run list
  DiagnosticScreen.java          In-game diagnostic log viewer with category filters and export

com.wynnsort.util/
  ScoreComputation.java          Central scoring algorithm: overall, weighted, single-stat, multi-filter
  FeatureLogger.java             Lightweight logger: auto-prepends [WS:Tag] prefix, dual output
  DiagnosticLog.java             Structured JSONL event logger with 1000-event ring buffer and 2MB file rotation
  DiagnosticEvent.java           POJO: single diagnostic event (timestamp, category, eventType, data, thread)
  PersistentLog.java             Append-only log file (wynnsort.log) with 5MB rotation
  ItemNameHelper.java            Extracts canonical item names from ItemStack via Wynntils models

com.wynnsort.mixin/
  WynntilsModMixin.java          Injects into WynntilsMod.init() to register all 10 event listeners
  TradeMarketScreenMixin.java    Injects sort button + stat input into TradeMarketSearchResultScreen
  ContainerScreenMixin.java      Injects stat filter, scale mode, preset UI into all container screens
```

---

## Initialization Sequence

1. **Fabric loads** `WynnSortMod.onInitializeClient()` (declared in `fabric.mod.json` under `entrypoints.client`).

2. **Logging init:** `PersistentLog.init()` opens `config/wynnsort/wynnsort.log` for append. `DiagnosticLog.init()` opens `config/wynnsort/diagnostics.jsonl` for append.

3. **Config load:** `WynnSortConfig.load()` reads `config/wynnsort.json` or creates defaults.

4. **Keybind registration:** 6 keybinds registered via `KeyBindingHelper.registerKeyBinding()`:
   - `toggleOverlayKey` (J), `openHistoryKey` (H), `openConfigKey` (;), `openLootrunHistoryKey` (L), `openDiagnosticsKey` (F8), `cyclePresetsKey` (P).

5. **Data store loading** (each wrapped in try/catch):
   - `TransactionStore.load()` -- starts `WynnSort-IO` daemon thread (5s save interval)
   - `MarketPriceStore.load()` -- starts `WynnSort-MarketIO` daemon thread (5s save interval)
   - `PriceHistoryStore.load()` -- starts `WynnSort-PriceHistoryIO` daemon thread (10s save interval)
   - `LootrunStore.load()` -- starts `WynnSort-LootrunIO` daemon thread (5s save interval)
   - `SearchPresetStore.load()` -- no IO thread (saves immediately on change)
   - `CrowdsourceCollector.INSTANCE.init()` -- starts `WynnSort-CrowdsourceFlush` daemon thread (configurable interval, default 5 min)

6. **Fabric event registration:**
   - `ClientTickEvents.END_CLIENT_TICK` -- keybind consumption loop
   - `HudRenderCallback.EVENT` -- `LootrunBeaconTracker.INSTANCE`, `LootrunSessionStats.INSTANCE`, `DryStreakTracker.INSTANCE`
   - `ScreenEvents.AFTER_INIT` + `ScreenEvents.afterRender` -- sell-screen buy-price overlay

7. **Wynntils event bus registration** (happens later, triggered by mixin):
   `WynntilsModMixin` injects into `WynntilsMod.init()` at `@At("RETURN")`. When Wynntils finishes its init, the mixin calls `WynntilsMod.registerEventListener()` for all 10 feature singletons:
   - `QualityOverlayFeature.INSTANCE`
   - `TradeMarketLogger.INSTANCE`
   - `MarketPriceFeature.INSTANCE`
   - `LootrunSessionStats.INSTANCE`
   - `LootrunHistoryFeature.INSTANCE`
   - `DryStreakTracker.INSTANCE`
   - `CrowdsourceCollector.INSTANCE`
   - `BestItemHighlightFeature.INSTANCE`
   - `TooltipFeature.INSTANCE`
   - `LootrunBeaconTracker.INSTANCE`

---

## Event Registration Map

### Wynntils Event Bus (`@SubscribeEvent`)

| Class | Method | Event Type | Purpose |
|-------|--------|------------|---------|
| `QualityOverlayFeature` | `onSlotRender` | `SlotRenderEvent.Post` | Render quality color + percentage + rank badge per slot |
| `BestItemHighlightFeature` | `onContainerRender` | `ContainerRenderEvent` | Draw gold border on best item |
| `TooltipFeature` | `onItemTooltipRender` | `ItemTooltipRenderEvent.Pre` | Inject score line into tooltips |
| `MarketPriceFeature` | `onContainerContent` | `ContainerSetContentEvent.Post` | Record prices from trade market results |
| `MarketPriceFeature` | `onTooltip` | `ItemTooltipRenderEvent.Pre` | Show cached price + trend in tooltips |
| `TradeMarketLogger` | `onTradeMarketState` | `TradeMarketStateEvent` | Log state transitions |
| `TradeMarketLogger` | `onScreenOpen` | `ScreenOpenedEvent.Post` | Log trade market screen opens |
| `TradeMarketLogger` | `onScreenClose` | `ScreenClosedEvent.Post` | Log trade market screen closes |
| `TradeMarketLogger` | `onContainerContent` | `ContainerSetContentEvent.Post` | Log + capture buy/sell context |
| `TradeMarketLogger` | `onSlotUpdate` | `ContainerSetSlotEvent.Post` | Log + capture price from slot updates |
| `TradeMarketLogger` | `onChatMessage` | `ChatMessageEvent.Match` | Detect "Finished buying/selling" + claim messages |
| `LootrunBeaconTracker` | `onLootrunCompleted` | `LootrunFinishedEvent.Completed` | Clear beacon state on run completion |
| `LootrunBeaconTracker` | `onLootrunFailed` | `LootrunFinishedEvent.Failed` | Clear beacon state on run failure |
| `LootrunSessionStats` | `onLootrunCompleted` | `LootrunFinishedEvent.Completed` | Capture end-of-run stats |
| `LootrunSessionStats` | `onLootrunFailed` | `LootrunFinishedEvent.Failed` | Capture failed run stats |
| `LootrunHistoryFeature` | `onLootrunCompleted` | `LootrunFinishedEvent.Completed` | Record run to LootrunStore |
| `LootrunHistoryFeature` | `onLootrunFailed` | `LootrunFinishedEvent.Failed` | Record failed run to LootrunStore |
| `DryStreakTracker` | `onLootrunCompleted` | `LootrunFinishedEvent.Completed` | Update dry streak with pull count |
| `DryStreakTracker` | `onLootrunFailed` | `LootrunFinishedEvent.Failed` | Reset transient state |
| `DryStreakTracker` | `onContainerContent` | `ContainerSetContentEvent.Post` | Scan reward chests for mythic items |
| `CrowdsourceCollector` | `onContainerContent` | `ContainerSetContentEvent.Post` | Collect price observations for crowdsource |

### Fabric Events

| Registration | Callback | Purpose |
|-------------|----------|---------|
| `ClientTickEvents.END_CLIENT_TICK` | Lambda in `WynnSortMod` | Consume keybind clicks, open screens |
| `HudRenderCallback.EVENT` | `LootrunBeaconTracker.INSTANCE` | Render beacon HUD + poll lootrun state |
| `HudRenderCallback.EVENT` | `LootrunSessionStats.INSTANCE` | Render session stats HUD + poll live stats |
| `HudRenderCallback.EVENT` | `DryStreakTracker.INSTANCE` | Render dry streak HUD during lootruns |
| `ScreenEvents.AFTER_INIT` | Lambda in `WynnSortMod` | Register sell-screen buy-price overlay |

---

## Mixin Details

### `WynntilsModMixin`

- **Target:** `com.wynntils.core.WynntilsMod` (non-vanilla)
- **Remap:** `false` (entire class)
- **@Shadow:** none
- **@Inject:** `method = "init"`, `at = @At("RETURN")` -- static method `wynnsort$onInitReturn(WynntilsMod.ModLoader, String, boolean, File, CallbackInfo)`
- **Purpose:** Register all 10 WynnSort event listeners on Wynntils' NeoForge EventBus after Wynntils finishes initializing.

### `TradeMarketScreenMixin`

- **Target:** `com.wynntils.screens.trademarket.TradeMarketSearchResultScreen`
- **Remap:** `true` (class-level, because it extends vanilla `Screen`)
- **@Shadow:** `itemSearchWidget` (remap = false) -- `ItemSearchWidget`
- **@Unique fields:** `SORT_PATTERN`, `wynnsort$sortButton`, `wynnsort$statInput`, `wynnsort$sortActive`
- **@Inject:** `method = "doInit"`, `at = @At("RETURN")`, `remap = false` -- adds sort button and stat input EditBox to the trade market search result screen
- **Purpose:** Sort toggle button + stat input field on the Wynntils trade market screen. Manages `sort:X` token injection/removal in the search widget.

### `ContainerScreenMixin`

- **Target:** `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen`
- **Remap:** `true` (vanilla class)
- **@Shadow:** `leftPos`, `topPos`, `imageWidth` -- protected int fields for container positioning
- **@Unique fields:** `wynnsort$statInput`, `wynnsort$noriButton`, `wynnsort$overallButton`, `wynnsort$statBoxes`, `wynnsort$statNames`, `wynnsort$presetButtons`, `wynnsort$presetNameInput`, etc.
- **@Inject methods:**
  - `method = "init"`, `at = @At("RETURN")` -- adds stat filter input, scale mode buttons, preset buttons
  - `method = "render"`, `at = @At("RETURN")` -- renders stat labels, preset tooltips, preset name input
  - `method = "mouseClicked"`, `at = @At("HEAD")`, cancellable -- intercepts right-click on preset buttons
  - `method = "keyPressed"`, `at = @At("HEAD")`, cancellable -- handles J (toggle overlay), P (cycle presets), Enter/Escape (preset name editing), input focus routing
- **Purpose:** Injects the entire stat filter + preset UI onto every container screen. Handles overlay toggle, preset save/apply/cycle, and scale mode switching.

---

## Feature Catalog

### QualityOverlayFeature

- **Singleton:** `QualityOverlayFeature.INSTANCE`
- **Wynntils events:** `SlotRenderEvent.Post`
- **Config flags:** `overlayEnabled`, `showPercentageText`, `useWeightedScale`
- **Rendering:** Colored 16x16 fill per slot (5 tiers: red/orange/yellow/green/cyan), percentage text, rank badges (#1 gold, #2 silver, #3 bronze). Renders at z=200 (color) and z=300 (text/badges).
- **Circuit breaker:** `scoreComputationBroken` -- disables overlay permanently if `ScoreComputation` throws `Exception` or `NoClassDefFoundError`.
- **Key APIs:** `Models.Item.getWynnItem()`, `GearItem.getItemInstance()`, `ScoreComputation.computeScore()`
- **Performance:** Rank computation cached per frame using `mc.getFrameTimeNs()`. Diagnostic logging sampled every 50th computation.

### BestItemHighlightFeature

- **Singleton:** `BestItemHighlightFeature.INSTANCE`
- **Wynntils events:** `ContainerRenderEvent`
- **Config flags:** `overlayEnabled`
- **Rendering:** 1px gold border (z=310) around the slot with the highest score.
- **Circuit breaker:** `scoreComputationBroken`
- **Reflection:** Resolves `AbstractContainerScreen.leftPos` / `topPos` via `setAccessible(true)` for positioning.

### TooltipFeature

- **Singleton:** `TooltipFeature.INSTANCE`
- **Wynntils events:** `ItemTooltipRenderEvent.Pre`
- **Config flags:** `overlayEnabled`
- **Rendering:** Injects line at index 1: `"WynnSort: XX% (label)"` with color-coded percentage (same tier colors as overlay).
- **Circuit breaker:** `scoreComputationBroken`
- **Key APIs:** `event.getTooltips()`, `event.setTooltips()` -- creates a mutable copy to avoid `UnsupportedOperationException`.

### MarketPriceFeature

- **Singleton:** `MarketPriceFeature.INSTANCE`
- **Wynntils events:** `ContainerSetContentEvent.Post`, `ItemTooltipRenderEvent.Pre`
- **Config flags:** `marketPriceCacheEnabled`, `priceHistoryEnabled`, `crowdsourceEnabled`, `tradeMarketBuyTaxPercent`, `marketPriceStalenessHours`
- **Recording:** On `ContainerSetContentEvent.Post`, checks `TradeMarketState` is `DEFAULT_RESULTS` or `FILTERED_RESULTS`. For each item, extracts base name via `ItemNameHelper`, gets price via `Models.TradeMarket.calculateItemPriceInfo()`, records to `MarketPriceStore` and `PriceHistoryStore`.
- **Tooltip injection:** Adds up to 4 lines: main price + age + trend arrow, buyer cost with tax, price range (if history), local crowdsource average, community data.
- **Price formatting:** `formatEmeralds()` -- uses stx (262144), le (4096), eb (64), e denominations.

### TradeMarketLogger

- **Singleton:** `TradeMarketLogger.INSTANCE`
- **Wynntils events:** `TradeMarketStateEvent`, `ScreenOpenedEvent.Post`, `ScreenClosedEvent.Post`, `ContainerSetContentEvent.Post`, `ContainerSetSlotEvent.Post`, `ChatMessageEvent.Match`
- **Config flags:** `tradeMarketLogging` (verbose logging), `tradeHistoryEnabled` (transaction capture)
- **Two responsibilities:**
  1. **Logging:** Structured `[WS:TM]` logs of every state transition, container snapshot, slot update, chat message, and Wynntils API value while the trade market is active.
  2. **Transaction capture:** Detects buy/sell completions via chat patterns ("Finished buying/selling [item]", "Visit the Trade Market to claim your items/emeralds"). Captures pending context (item name, price, stat fingerprint) during BUYING/SELLING states, then commits to `TransactionStore` on confirmation.
- **Price extraction:** First tries `Models.TradeMarket.calculateItemPriceInfo()`, then falls back to regex parsing of item lore (LORE_PRICE_PATTERN, LORE_EMERALD_PATTERN, DENOM_PATTERN).
- **Stat fingerprint:** `buildStatFingerprint()` -- creates `"v1:apiName:value:stars,..."` sorted by apiName for identified gear matching.
- **Sell overlay:** Sets `matchedBuyRecord` when entering SELLING state with a matching buy record in history.

### LootrunBeaconTracker

- **Singleton:** `LootrunBeaconTracker.INSTANCE`
- **Implements:** `HudRenderCallback` (Fabric)
- **Wynntils events:** `LootrunFinishedEvent.Completed`, `LootrunFinishedEvent.Failed`
- **Config flags:** `lootrunHudEnabled`
- **Tracking:** Uses poll-based approach via `onHudRender()` each tick. Monitors `Models.Lootrun.getState()` for state transitions. Detects beacon selection on `CHOOSING_BEACON -> IN_TASK` transition using `Models.Lootrun.getLastTaskBeaconColor()` with fallback to orange/rainbow count comparison.
- **Beacon state:**
  - `orangeBeacons` -- `List<Integer>` of remaining challenge counts per active orange beacon
  - `rainbowRemaining` -- remaining challenges for rainbow effect (-1 = inactive)
  - `aquaPending` / `aquaWasVibrant` -- aqua boost state for next beacon
  - `beaconCounts` -- `EnumMap<LootrunBeaconKind, Integer>` for non-duration beacons
  - `beaconChoiceLog` -- `List<BeaconChoice>` recording every selection
- **Duration calculation:** `calculateDuration(base, isVibrant)`: vibrant doubles base, aqua multiplies by 2 (or 3 if aqua was vibrant).
- **State persistence:** Saves to `config/wynnsort-lootrun-state.json` on state transitions and every 60s. Restores on relog if state file is <120 minutes old.
- **HUD:** Left side, vertically centered. Semi-transparent background. Color-coded countdown (green >15, orange 6-15, red <=5).

### LootrunSessionStats

- **Singleton:** `LootrunSessionStats.INSTANCE`
- **Implements:** `HudRenderCallback` (Fabric)
- **Wynntils events:** `LootrunFinishedEvent.Completed`, `LootrunFinishedEvent.Failed`
- **Config flags:** `lootrunStatsHudEnabled`
- **Polling:** Each `onHudRender()` tick, reads `Models.Lootrun.getChallenges()`, `getRerolls()`, `getSacrifices()`. Detects beacon selection on `CHOOSING_BEACON -> IN_TASK`. Tracks aqua multiplier for pull calculation.
- **Session data:** `LootrunSessionData` POJO with challenges, pulls, rerolls, sacrifices, mobs, chests, XP, beacon summary, duration.
- **Post-run display:** Shows last session data for 10 seconds after run ends.
- **HUD:** Right side, top corner. 150px wide.

### DryStreakTracker

- **Singleton:** `DryStreakTracker.INSTANCE`
- **Implements:** `HudRenderCallback` (Fabric)
- **Wynntils events:** `LootrunFinishedEvent.Completed`, `LootrunFinishedEvent.Failed`, `ContainerSetContentEvent.Post`
- **Config flags:** `dryStreakEnabled`
- **Mythic detection:** Scans container contents during active lootruns for `GearTier.MYTHIC` items. Only scans containers whose class name contains "LootrunRewardChest" or title contains "lootrun reward".
- **Data persistence:** `DryStreakData` saved to `config/wynnsort/dry_streak.json` -- tracks `totalPullsWithoutMythic`, `totalLifetimePulls`, `mythicsFound`, `longestDryStreak`, `lastMythicName/Timestamp`.
- **HUD:** Bottom-right, above hotbar. Color-coded streak: white (<1250), yellow (1250-2499), orange (2500-4999), red (>=5000). Shows percentile context ("X% would have found one by now") based on 1/2500 mythic rate.

### LootrunHistoryFeature

- **Singleton:** `LootrunHistoryFeature.INSTANCE`
- **Wynntils events:** `LootrunFinishedEvent.Completed`, `LootrunFinishedEvent.Failed`
- **Config flags:** `lootrunHistoryEnabled`
- **Purpose:** Pure data recorder. Creates `LootrunRecord` with all stats from the event and adds it to `LootrunStore`.

### CrowdsourceCollector

- **Singleton:** `CrowdsourceCollector.INSTANCE`
- **Wynntils events:** `ContainerSetContentEvent.Post`
- **Config flags:** `crowdsourceEnabled`, `crowdsourceFlushMinutes`
- **Collection:** On trade market search results, extracts `CrowdsourceEntry` for each item (name, type, rarity, price, quantity, identified, overallPct, timestamp, modVersion). Adds to `CrowdsourceQueue` (dedup by item+price+quantity).
- **Flushing:** `WynnSort-CrowdsourceFlush` daemon thread drains the queue at configurable intervals (default 5 min) and calls `CrowdsourceClient.INSTANCE.submitBatch()`.
- **Circuit breaker:** `flushBroken` -- disables flush on class loading failures.
- **Shutdown hook:** Flushes remaining data and calls `CrowdsourceClient.INSTANCE.shutdown()`.

---

## Scoring Algorithm

`ScoreComputation.computeScore(GearItem gearItem, GearInstance gearInstance, List<StatFilter> filters)`:

1. **Overall mode** (`SortState.isOverall()` -- filters list is empty):
   - If `WynnSortConfig.INSTANCE.useWeightedScale` is true, tries `WeightedScoreHelper.compute(gearItem)` which calls into Wynntils' ItemWeight APIs (Wynnpool/Nori community weights). Returns weighted score if available and >0.
   - Falls back to `gearInstance.getOverallPercentage()` -- Wynntils' built-in average of all stat roll percentages.

2. **Filter mode** (`SortState.isFilterMode()` -- at least one filter has a threshold operator):
   - For each `StatFilter`, calls `resolveStatPercentage(gearItem, gearInstance, filter.statPattern())`.
   - If any stat returns `NaN` (stat not found on item), the entire score is `NaN`.
   - If a filter has a threshold and the stat doesn't pass, returns `NaN` (item filtered out).
   - Otherwise returns the average of all matching stat percentages.

3. **Single-stat mode** (one filter, no threshold):
   - Returns `resolveStatPercentage(gearItem, gearInstance, filters.get(0).statPattern())`.

`resolveStatPercentage()`: Iterates `gearInstance.identifications()`, matches each `StatActualValue` against the pattern string (case-insensitive contains match on `apiName`, `displayName`, and `key`). Calls `StatCalculator.getPercentage(actual, possible)` for the matching stat.

`statMatches(StatActualValue actual, String target)`: Exact match or contains match on lowercase `apiName`, `displayName`, and `key`.

`StatFilter.parse(String input)`: Splits on `,`, matches each part against `(\w+)\s*(>=|<=|>|<)\s*(\d+(\.\d+)?)`. If no operator found, creates a `NONE`-operator filter for display-only mode.

---

## Config Fields Reference

All fields are in `WynnSortConfig` at `config/wynnsort.json`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `overlayEnabled` | `boolean` | `true` | Master toggle for quality overlay, tooltip scores, and best-item highlight |
| `showPercentageText` | `boolean` | `true` | Show percentage number text on quality overlay |
| `sortButtonEnabled` | `boolean` | `true` | Show sort toggle button on trade market screen |
| `lastFilter` | `String` | `""` | Last-used stat filter text (persisted across sessions) |
| `useWeightedScale` | `boolean` | `true` | Use Nori/Wynnpool weighted scoring vs. overall average |
| `lootrunHudEnabled` | `boolean` | `true` | Show beacon tracker HUD during lootruns |
| `lootrunStatsHudEnabled` | `boolean` | `true` | Show session stats HUD during lootruns |
| `lootrunHistoryEnabled` | `boolean` | `true` | Record lootrun completion/failure history |
| `tradeHistoryEnabled` | `boolean` | `true` | Log trade market transactions to disk |
| `tradeHistoryMinPriceFilter` | `long` | `5000` | Minimum price filter for trade history display (emeralds) |
| `tradeMarketBuyTaxPercent` | `int` | `5` | Trade market buyer tax percentage |
| `tradeMarketLogging` | `boolean` | `true` | Verbose trade market logging (every state change, container, slot, chat) |
| `dryStreakEnabled` | `boolean` | `true` | Track mythic dry streak across sessions |
| `marketPriceCacheEnabled` | `boolean` | `true` | Enable market price caching and tooltip display |
| `marketPriceStalenessHours` | `int` | `168` | Hours before cached price is considered stale (168 = 7 days) |
| `searchPresetsEnabled` | `boolean` | `true` | Enable saved search presets |
| `priceHistoryEnabled` | `boolean` | `true` | Enable price history tracking with trend analysis |
| `priceHistoryMaxDays` | `int` | `30` | Maximum days to retain price history entries |
| `crowdsourceEnabled` | `boolean` | `true` | Enable crowdsourced market data collection |
| `crowdsourceApiUrl` | `String` | `""` | Remote API URL (empty = local only) |
| `crowdsourceFlushMinutes` | `int` | `5` | Minutes between crowdsource queue flushes |
| `diagnosticLoggingEnabled` | `boolean` | `true` | Enable structured diagnostic logging |

---

## Persistent Data Files

All files are relative to `.minecraft/config/`.

| File Path | Format | Writer Class | Max Size / Retention | Save Strategy |
|-----------|--------|-------------|---------------------|---------------|
| `wynnsort.json` | JSON | `WynnSortConfig` | Unbounded (small) | Immediate on change |
| `wynnsort/transactions.json` | JSON array | `TransactionStore` | 5000 entries max | Async 5s dirty check (`WynnSort-IO` thread) |
| `wynnsort/market_prices.json` | JSON map | `MarketPriceStore` | Auto-evict stale entries (staleness config) | Async 5s dirty check (`WynnSort-MarketIO` thread) |
| `wynnsort/price_history.json` | JSON map | `PriceHistoryStore` | 100 entries/item, evict after `priceHistoryMaxDays` | Async 10s dirty check (`WynnSort-PriceHistoryIO` thread) |
| `wynnsort/lootrun_history.json` | JSON array | `LootrunStore` | 500 entries max | Async 5s dirty check (`WynnSort-LootrunIO` thread) |
| `wynnsort/search_presets.json` | JSON array | `SearchPresetStore` | 10 entries max | Immediate on change (no IO thread) |
| `wynnsort/dry_streak.json` | JSON | `DryStreakTracker` | Single object (small) | Immediate on lootrun completion |
| `wynnsort/crowdsource_data.json` | JSON map | `CrowdsourceClient` | Unbounded (grows with observations) | On queue flush (configurable interval) |
| `wynnsort-lootrun-state.json` | JSON | `LootrunBeaconTracker` | Single object (small) | On beacon selection + every 60s during active run |
| `wynnsort/wynnsort.log` | Text log | `PersistentLog` | 5 MB rotation (keeps `.log.old`) | Immediate flush per write |
| `wynnsort/diagnostics.jsonl` | JSONL | `DiagnosticLog` | 2 MB rotation (keeps `.jsonl.old`) | Immediate flush per event |

---

## Thread Model

All mod code runs on the **Minecraft client render thread** unless otherwise noted. Named IO threads are daemon threads created via `Executors.newSingleThreadScheduledExecutor()`:

| Thread Name | Owner Class | Purpose | Interval |
|-------------|-------------|---------|----------|
| `WynnSort-IO` | `TransactionStore` | Save transactions to disk if dirty | 5 seconds |
| `WynnSort-MarketIO` | `MarketPriceStore` | Save market prices + evict stale entries | 5 seconds |
| `WynnSort-PriceHistoryIO` | `PriceHistoryStore` | Save price history + evict old entries | 10 seconds |
| `WynnSort-LootrunIO` | `LootrunStore` | Save lootrun records to disk if dirty | 5 seconds |
| `WynnSort-CrowdsourceFlush` | `CrowdsourceCollector` | Drain queue and submit to CrowdsourceClient | Configurable (default 5 min) |
| `WynnSort-CrowdsourceShutdown` | `CrowdsourceCollector` | JVM shutdown hook: final flush + client shutdown | One-shot |

**Thread safety mechanisms:**
- `TransactionStore`, `LootrunStore`, `SearchPresetStore`: `CopyOnWriteArrayList` for reads + `AtomicBoolean` dirty flag
- `MarketPriceStore`: `ConcurrentHashMap` + `AtomicBoolean` dirty flag
- `PriceHistoryStore`: `ConcurrentHashMap` with `Collections.synchronizedList()` inner lists
- `CrowdsourceClient`: `ConcurrentHashMap` for local DB and community cache, `AtomicLong` for rate limiting
- `CrowdsourceQueue`: `ConcurrentHashMap.newKeySet()` for O(1) dedup
- `DiagnosticLog`: `ReentrantLock` protecting ring buffer and file writer
- `PersistentLog`: `synchronized` methods
- `TradeMarketLogger.matchedBuyRecord`: `volatile` field

---

## Design Patterns

### Circuit Breaker

Used in `QualityOverlayFeature`, `BestItemHighlightFeature`, `TooltipFeature`, `CrowdsourceCollector`. A boolean flag (e.g., `scoreComputationBroken`, `flushBroken`) is set to `true` on first `Exception` or `NoClassDefFoundError`. Once tripped, the feature is permanently disabled until game restart. Prevents log spam and performance degradation from repeated failures.

### Lazy Class Loading

`TradeMarketSortHelper` isolates Wynntils class references (`TradeMarketSearchResultScreen`, `ItemSearchWidget`) so they are only loaded when the user actively presses the sort keybind while on a trade market screen. This avoids `NoClassDefFoundError` during mod startup if Wynntils classes are not yet available.

`ScoreComputation.getWeightedScore()` calls `WeightedScoreHelper.compute()` inside a `try/catch(Throwable)`. If the helper class doesn't exist (Wynntils version mismatch), it silently returns `NaN`.

### Async Persistence (Dirty Flag Pattern)

Used by `TransactionStore`, `MarketPriceStore`, `PriceHistoryStore`, `LootrunStore`. A daemon IO thread runs `saveIfDirty()` at fixed intervals. Mutations set an `AtomicBoolean` dirty flag. The save method atomically clears the flag with `compareAndSet(true, false)` and writes to disk only if it was dirty. This batches writes and avoids file IO on the render thread.

### Singleton + Event Bus

All 10 Wynntils-registered features use the singleton pattern (`public static final X INSTANCE = new X()` with private constructor). Singletons are registered on the Wynntils NeoForge event bus via `WynntilsMod.registerEventListener()`. The HUD overlays are also registered on Fabric's `HudRenderCallback.EVENT`.

### FeatureLogger Convention

Each feature creates a `FeatureLogger` at the class level:
```java
private static final FeatureLogger LOG = new FeatureLogger("Tag", DiagnosticLog.Category.XXX);
```
`FeatureLogger` prepends `[WS:Tag]` to all messages and routes to both SLF4J (`latest.log`) and `PersistentLog` (`wynnsort.log`). The `event()` method also emits a structured `DiagnosticLog` event.

### Diagnostic Events (Structured Logging)

`DiagnosticLog` maintains a 1000-event ring buffer in memory and writes JSONL to disk. Events have: `timestamp`, `category` (enum), `eventType` (string), `data` (Map), `threadName`. The `DiagnosticScreen` reads from the ring buffer for real-time display with category filtering.

Categories: `TRADE_MARKET`, `LOOTRUN`, `OVERLAY`, `BEACON`, `CONFIG`, `STARTUP`, `ERROR`, `TOOLTIP`, `HIGHLIGHT`, `MARKET_PRICE`, `CROWDSOURCE`, `DRY_STREAK`, `PERSISTENCE`, `MIXIN`.

### Deduplication

- `CrowdsourceQueue`: Uses `CrowdsourceEntry.deduplicationKey()` = `"itemName|listingPrice|quantity"` with `ConcurrentHashMap.newKeySet()`.
- `TransactionStore.addTransaction()`: Checks last 5 records for same type + base name + price within 10 seconds.
- `PriceHistoryStore.record()`: Skips same price within 1 hour dedup window.

### Stat Fingerprinting

`TradeMarketLogger.buildStatFingerprint()` creates a deterministic string from identified gear stats: `"v1:apiName1:value1:stars1,apiName2:value2:stars2,..."` sorted by apiName. Used by `TransactionStore.findMatchingBuy()` and `pairTransactions()` for exact item matching between buy and sell records. Fingerprints are version-prefixed; `fingerprintsMatch()` strips `v1:` prefix for cross-version compatibility.

---

## Keybindings

All registered in `WynnSortMod.onInitializeClient()` via `KeyBindingHelper.registerKeyBinding()` under category `"category.wynnsort"`.

| Default Key | Mapping Name | Action | Handler |
|-------------|-------------|--------|---------|
| J | `key.wynnsort.toggle_overlay` | Toggle quality overlay on/off | `WynnSortMod.onToggleOverlay()` + `ContainerScreenMixin.keyPressed()` |
| H | `key.wynnsort.open_history` | Open `TransactionHistoryScreen` | `WynnSortMod` tick handler |
| ; (semicolon) | `key.wynnsort.open_config` | Open `WynnSortConfigScreen` | `WynnSortMod` tick handler |
| L | `key.wynnsort.open_lootrun_history` | Open `LootrunHistoryScreen` | `WynnSortMod` tick handler |
| F8 | `key.wynnsort.open_diagnostics` | Open `DiagnosticScreen` | `WynnSortMod` tick handler |
| P | `key.wynnsort.cycle_presets` | Cycle through saved search presets (in container) | `ContainerScreenMixin.keyPressed()` (consumed in tick handler to prevent queue buildup) |

---

## Related Documentation

- `CLAUDE.md` -- Build commands, deployment, coding conventions, attribution policy
- `FEATURES.md` -- User-facing feature descriptions
- `wynntils-api-reference.md` -- Wynntils API quick reference: Models, Services, events, item hierarchy
- `wynncraft-knowledge.md` -- Game mechanics: beacons, mythic rates, tax, currency
