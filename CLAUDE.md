# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**WynnSort** — a Fabric Minecraft mod that adds quality-based sorting and visual overlays to Wynntils' trade market screen on Wynncraft.

## Build & Run

```bash
./gradlew build          # Build the mod JAR (output: build/libs/wynnsort-*.jar)
./gradlew runClient      # Launch Minecraft with the mod in dev mode
./gradlew clean          # Clean build artifacts
```

**Prerequisites:**
- Java 21
- Wynntils JAR in `libs/wynntils-3.4.5-fabric+MC-1.21.4.jar` (copied from Prism Launcher instance or downloaded from GitHub releases)

## Target Environment

- Minecraft 1.21.4, Fabric Loader 0.18.0, Fabric API 0.119.4
- Wynntils 3.4.5 (compile-only dependency, users install separately)
- Uses Mojang mappings (not Yarn) to match Wynntils class names

## Architecture

The mod has 3 functional layers that all piggyback on Wynntils' infrastructure:

### 1. Wynntils Event Bus Integration
- `WynntilsModMixin` injects into `WynntilsMod.init()` at RETURN to register our `QualityOverlayFeature` on Wynntils' NeoForge EventBus
- This is the same pattern used by WynnVentory and other Wynntils companion mods
- `@SubscribeEvent` from `net.neoforged.bus.api` (bundled by Wynntils at runtime)

### 2. Quality Overlay Rendering (`QualityOverlayFeature`)
- Subscribes to Wynntils' `SlotRenderEvent.Post` event
- For each slot in the trade market screen, resolves `ItemStack` → `WynnItem` → `GearItem` → `GearInstance` → `getOverallPercentage()`
- Renders colored backgrounds (5-tier: red/orange/yellow/green/cyan) and percentage text
- Only active on `TradeMarketSearchResultScreen`
- This is one of 10+ features registered on the Wynntils event bus (see `WynntilsModMixin` for the full list)

### 3. Sort Toggle (`TradeMarketScreenMixin` + keybind)
- Mixin injects a "Sort: Quality" button into `TradeMarketSearchResultScreen.doInit()`
- Toggling appends/removes `sort:overall` in Wynntils' `ItemSearchWidget` text
- `sort:overall` is a real Wynntils query directive — `OverallStatProvider` provides the data, `ItemFilterService.filterAndSort()` does the sorting
- Keybind (K) provides keyboard shortcut via `TradeMarketSortHelper` (lazy-loads Wynntils classes to avoid startup issues)
- Extended with stat filtering, named presets, and weighted/overall scale mode toggle

See `ARCHITECTURE.md` for comprehensive technical reference covering all features, data stores, and patterns.

### Key Wynntils Classes Used

| Class | Purpose |
|-------|---------|
| `Models.Item.getWynnItem(ItemStack)` | Resolve Wynntils item annotation |
| `GearItem.getItemInstance()` | Get identified gear data |
| `GearInstance.getOverallPercentage()` | Average roll % across all stats |
| `SlotRenderEvent.Post` | Hook into container slot rendering |
| `ItemSearchWidget` | Search/sort query input on trade market |
| `WynntilsMod.registerEventListener()` | Register on Wynntils' event bus |

### Mixin Notes
- `WynntilsModMixin`: targets non-vanilla class, uses `remap = false`
- `TradeMarketScreenMixin`: targets Wynntils class but extends vanilla `Screen`, so class-level remap stays ON; individual `@Shadow`/`@Inject` targeting Wynntils methods use `remap = false`

## Config

JSON config at `.minecraft/config/wynnsort.json` with 20+ fields covering overlay, trade market, lootrun, crowdsource, and diagnostic settings. See `ARCHITECTURE.md` for complete config reference. Loaded via Gson, no external config library.

## Deployment

**After every code change**, always rebuild and deploy the JAR:

```bash
./gradlew build && cp build/libs/wynnsort-1.0.0.jar "C:\Users\sebal\AppData\Roaming\PrismLauncher\instances\Wynncraft 101\minecraft\mods\wynnsort-1.0.0.jar"
```

The user tests in the Prism Launcher "Wynncraft 101" instance. Minecraft logs are at:
`C:\Users\sebal\AppData\Roaming\PrismLauncher\instances\Wynncraft 101\minecraft\logs\latest.log`

## Attribution Policy

**Never mention Claude, Anthropic, or any AI tool in commit messages, PR descriptions, code comments, READMEs, changelogs, or any other public-facing content.** This includes Co-Authored-By trailers, generated-by footers, and similar markers. Write commit messages as if a human authored them.

## Local Knowledge Files

Before implementing features or debugging issues, consult these local reference files first. They contain researched, verified information that avoids the need for external lookups.

| File | Contents |
|------|----------|
| `ARCHITECTURE.md` | Complete technical reference: package structure, all features, config fields, data stores, design patterns, initialization, thread model |
| `FEATURES.md` | User-facing feature list with keybinds and descriptions |
| `wynntils-api-reference.md` | Wynntils API quick reference: Models, Services, events, item type hierarchy, state machines, registration patterns, and common pitfalls (class isolation, circuit breakers, thread safety) |
| `wynncraft-knowledge.md` | Wiki-sourced game mechanics: beacon types/effects/vibrant/aqua stacking, mythic drop rates (1/2500), trade market tax (5%), currency denominations, dry streak statistics |
| `wynncraft-mod-research.md` | Wynntils codebase deep dive: trade market architecture, item stat parsing pipeline, filter system internals, DPS tracking analysis, community feature requests, mod ecosystem, and future feature ideas |

## Documentation Map

| Task | Read First |
|------|-----------|
| Understanding the full mod architecture | `ARCHITECTURE.md` |
| Adding a new feature or event listener | `ARCHITECTURE.md` (registration, patterns, init sequence) |
| Working with Wynntils APIs | `wynntils-api-reference.md` |
| Implementing game mechanics | `wynncraft-knowledge.md` |
| Planning future features | `wynncraft-mod-research.md` |
| Updating user-facing feature docs | `FEATURES.md` |
