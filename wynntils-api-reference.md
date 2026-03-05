# Wynntils API Reference for WynnSort

Quick reference for Wynntils classes, events, and APIs used or available for WynnSort development.
Consult this before implementing features or debugging issues that touch Wynntils internals.

## Core Entry Points

### Models (com.wynntils.core.components.Models)

Singleton access to all Wynntils data models. Always accessed as `Models.<ModelName>`.

| Model | Key Methods | Notes |
|-------|------------|-------|
| `Models.Item` | `getWynnItem(ItemStack) -> Optional<WynnItem>` | Resolves any ItemStack to Wynntils' typed item wrapper |
| `Models.Lootrun` | `getState()`, `getChallenges()`, `getLastTaskBeaconColor()`, `wasLastBeaconVibrant()`, `getActiveOrangeBeacons()`, `getActiveRainbowBeacons()`, `getBeaconCount(kind)`, `getRerolls()`, `getSacrifices()` | Central lootrun state API |
| `Models.TradeMarket` | `getTradeMarketState()`, `calculateItemPriceInfo(ItemStack)`, `inTradeMarket()`, `inChatInput()`, `getUnitPrice()`, `getSoldItemName()`, `getPriceCheckInfo()`, `getLastSearchFilter()` | Trade market state machine + price extraction + session queries |
| `Models.Gear` | `parseInstance(GearInfo, ItemStack)` | Parse an item into GearInstance with stat rolls |
| `Models.Container` | `getCurrentContainer()` | Get current open container metadata |

### Container Model (com.wynntils.models.containers)

`Models.Container.getCurrentContainer()` returns a `Container` object (or null) representing the currently open server-side container. Used for container type detection in DryStreakTracker and TradeMarketLogger.

```java
Container container = Models.Container.getCurrentContainer();
if (container != null) {
    container.getContainerName()        // -> String (the container title)
    container.getContainerId()          // -> int (the network container ID)
    container.getClass().getSimpleName() // -> String (for type detection)
    container.getClass().getName()       // -> String (fully qualified class name)
}
```

**Container type detection pattern** (used in DryStreakTracker):
```java
Container container = Models.Container.getCurrentContainer();
if (container != null) {
    String className = container.getClass().getSimpleName();
    String containerName = container.getContainerName();
    boolean isRewardChest = className.contains("LootrunRewardChest")
            || (containerName != null && containerName.toLowerCase().contains("lootrun reward"));
}
```

### Services (com.wynntils.core.components.Services)

Higher-level services built on Models. Some may not exist in all Wynntils versions.

| Service | Key Methods | Notes |
|---------|------------|-------|
| `Services.ItemFilter` | `filterAndSort(query, items)` | Client-side item filtering with query syntax |
| `Services.ItemWeight` | `getItemWeighting(name, source)`, `calculateWeighting(weighting, gearItem)` | Wynnpool/Nori weighted scoring. **May not exist in all versions** -- isolate imports (see WeightedScoreHelper pattern) |

#### ItemWeightSource Enum

```
ItemWeightSource.WYNNPOOL  // community-contributed stat weights
ItemWeightSource.NORI      // Nori's stat weights
```

Note: `Services.ItemWeight`, `ItemWeightSource`, and `ItemWeighting` may not exist in all Wynntils versions. Always isolate usage into a separate helper class loaded via `try { ... } catch (Throwable t)`. See `ScoreComputation.getWeightedScore()` for the pattern.

## Item Type Hierarchy

```
WynnItem (abstract base)
  +-- GearItem           -- weapons, armor, accessories
  |     .getItemInfo()   -> GearInfo (static item data: name, tier, base stats, requirements)
  |     .getItemInstance() -> Optional<GearInstance> (roll-specific data, empty if unidentified)
  |     .getGearTier()   -> GearTier (MYTHIC, LEGENDARY, RARE, etc.)
  |     .getName()       -> String
  +-- IngredientItem     -- crafting ingredients
  |     .getIngredientInfo() -> IngredientInfo (.tier(), .name())
  +-- EmeraldItem        -- currency items
  +-- GameItem           -- generic game items (potions, scrolls, etc.)
```

### GearTier Enum (com.wynntils.models.gear.type.GearTier)

```
GearTier: MYTHIC, LEGENDARY, RARE, UNIQUE, SET, NORMAL
```

Used for tier-based detection. Two equivalent access patterns:
```java
gearItem.getGearTier() == GearTier.MYTHIC       // via GearItem convenience method
gearItem.getItemInfo().tier() == GearTier.MYTHIC // via GearInfo
```

### GearInfo (static item data)

```java
GearInfo gearInfo = gearItem.getItemInfo();
gearInfo.name()                  // -> String (canonical item name)
gearInfo.tier()                  // -> GearTier
gearInfo.getPossibleValues(statType) // -> StatPossibleValues (for percentage calculation)
gearInfo.variableStats()         // -> List<Pair<StatType, StatPossibleValues>>
```

### GearInstance (identified gear data)

```java
GearInstance {
    .getOverallPercentage()  -> float  // average roll % across all stats (0-100)
    .identifications()       -> List<StatActualValue>  // individual stat rolls
    // also: powders, rerolls, shinyStat, setInstance
}
```

### StatActualValue (individual stat roll)

```java
StatActualValue {
    .statType()     -> StatType   // .getApiName(), .getDisplayName(), .getKey()
    .value()        -> int        // actual rolled value
    .stars()        -> int        // star count
    .internalRoll() -> RangedValue
}
```

### Computing stat roll percentage

```java
GearInfo gearInfo = gearItem.getItemInfo();
StatPossibleValues possible = gearInfo.getPossibleValues(actual.statType());
float percentage = StatCalculator.getPercentage(actual, possible);
```

## Events (subscribe via @SubscribeEvent on Wynntils event bus)

### Container/Screen Events

| Event | When | Key Data |
|-------|------|----------|
| `ContainerSetContentEvent.Post` | Container inventory updated | `.getItems()` -> List<ItemStack>, `.getContainerId()`, `.getStateId()` |
| `ContainerSetSlotEvent.Post` | Individual slot updated | `.getItemStack()`, `.getSlot()`, `.getContainerId()` |
| `ContainerRenderEvent` | Container screen renders | `.getScreen()`, `.getGuiGraphics()` |
| `SlotRenderEvent.Post` | Individual slot rendered | `.getSlot()`, `.getGuiGraphics()` |
| `ItemTooltipRenderEvent.Pre` | Tooltip about to render | `.getItemStack()`, `.getTooltips()`, `.setTooltips()` |
| `ScreenOpenedEvent.Post` | Screen opened (post-init) | `.getScreen()` |
| `ScreenClosedEvent.Post` | Screen closed | (no payload) |

### Lootrun Events

| Event | When | Key Data |
|-------|------|----------|
| `LootrunFinishedEvent.Completed` | Lootrun completed successfully | `.getRewardPulls()`, `.getChallengesCompleted()`, `.getTimeElapsed()`, `.getRewardRerolls()`, `.getRewardSacrifices()`, `.getMobsKilled()`, `.getChestsOpened()`, `.getExperienceGained()` |
| `LootrunFinishedEvent.Failed` | Lootrun failed | `.getChallengesCompleted()`, `.getTimeElapsed()` |

### Trade Market Events

| Event | When | Key Data |
|-------|------|----------|
| `TradeMarketStateEvent` | Trade market state changes | `.getOldState()` -> TradeMarketState, `.getNewState()` -> TradeMarketState |

### Chat Events

| Event | When | Key Data |
|-------|------|----------|
| `ChatMessageEvent.Match` | Chat message matched a handler pattern | `.getMessage()` -> Component, `.getMessageType()` |

Note: `ChatMessageEvent.Match` is from `com.wynntils.handlers.chat.event`, not `com.wynntils.mc.event`. Used by TradeMarketLogger for buy/sell transaction detection via server chat messages like "Finished buying [item]." and "Visit the Trade Market to claim your items."

## Lootrun State Machine

```
LootrunningState enum:
  NOT_RUNNING      -- no active lootrun
  CHOOSING_BEACON  -- beacon selection screen showing
  IN_TASK          -- actively fighting a challenge
  (others)         -- transitional states
```

State transitions to detect:
- `NOT_RUNNING -> *` = lootrun started
- `CHOOSING_BEACON -> IN_TASK` = beacon was selected (poll getLastTaskBeaconColor())
- `* -> NOT_RUNNING` = lootrun ended (but prefer LootrunFinishedEvent for clean data)
- `isRunning()` returns true for all active states

### Lootrun Model Methods (com.wynntils.core.components.Models.Lootrun)

```java
Models.Lootrun.getState()                          // -> LootrunningState
Models.Lootrun.getChallenges()                     // -> CappedValue (.current(), .max())
Models.Lootrun.getLastTaskBeaconColor()            // -> LootrunBeaconKind (which beacon was just selected)
Models.Lootrun.wasLastBeaconVibrant()              // -> boolean (was the selected beacon vibrant)
Models.Lootrun.getActiveOrangeBeacons()            // -> int (count of currently active orange beacons)
Models.Lootrun.getActiveRainbowBeacons()           // -> int (count of currently active rainbow beacons)
Models.Lootrun.getBeaconCount(LootrunBeaconKind)   // -> int (usage count of a specific beacon type)
Models.Lootrun.getChallengesTillNextOrangeExpires() // -> int (remaining challenges until oldest orange expires)
Models.Lootrun.getRerolls()                        // -> int (reward rerolls earned this run)
Models.Lootrun.getSacrifices()                     // -> int (reward sacrifices this run)
```

### Beacon Types (LootrunBeaconKind enum)

```
GREEN, YELLOW, BLUE, PURPLE, GRAY, ORANGE, RED,
DARK_GRAY, WHITE, AQUA, CRIMSON, RAINBOW
```

## Trade Market State Machine

```
TradeMarketState enum:
  NOT_ACTIVE        -- trade market not open
  DEFAULT_RESULTS   -- showing default search results
  FILTERED_RESULTS  -- showing filtered search results
  BUYING            -- in the purchase flow
  SELLING           -- in the selling flow
  (others)          -- price check, other states
```

### Trade Market Model Methods (com.wynntils.core.components.Models.TradeMarket)

```java
Models.TradeMarket.getTradeMarketState()           // -> TradeMarketState
Models.TradeMarket.calculateItemPriceInfo(stack)    // -> TradeMarketPriceInfo
Models.TradeMarket.inTradeMarket()                  // -> boolean (is a trade market screen open)
Models.TradeMarket.inChatInput()                    // -> boolean (is chat input active in TM)
Models.TradeMarket.getUnitPrice()                   // -> int (unit price for current listing)
Models.TradeMarket.getSoldItemName()                // -> String (name of the item being sold)
Models.TradeMarket.getPriceCheckInfo()              // -> PriceCheckInfo (.recommendedPrice(), .bid(), .ask())
Models.TradeMarket.getLastSearchFilter()            // -> String (last search query text)
```

### Price Extraction

```java
TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(stack);
// priceInfo.price() -> long (per-unit emerald price)
// priceInfo.amount() -> int (quantity)
// priceInfo.totalPrice() -> long (price * amount)
// priceInfo.silverbullPrice() -> long (silverbull valuation)
// Check: priceInfo != null && priceInfo != TradeMarketPriceInfo.EMPTY && priceInfo.price() > 0
```

## Trade Market Screen Internals

`TradeMarketSearchResultScreen` extends Wynntils' base screen:
- Contains `ItemSearchWidget` for query input (filter/sort syntax)
- Query syntax: `walkSpeed:>=80%`, `sort:overall`, etc.
- Pages loaded in batches of 10 into `itemMap` (page -> slot -> ItemStack)
- `Services.ItemFilter.filterAndSort()` does client-side filtering

## Registration Pattern

WynnSort registers on Wynntils' NeoForge EventBus via mixin:

```java
// WynntilsModMixin injects at RETURN of WynntilsMod.init()
WynntilsMod.registerEventListener(featureInstance);
```

All `@SubscribeEvent` methods must use `net.neoforged.bus.api.SubscribeEvent` (bundled by Wynntils).

## Common Patterns & Pitfalls

### Isolate optional Wynntils APIs
Some Wynntils classes (e.g., `Services.ItemWeight`, `ItemWeightSource`, `ItemWeighting`) may not exist in all versions. If a class imports them, the entire class fails to load at runtime. **Always** isolate optional API usage into a separate helper class and call it via `try { ... } catch (Throwable t)`. See `WeightedScoreHelper.java` for the pattern.

### Circuit breakers for render-path code
Any code on the render path (SlotRenderEvent, ContainerRenderEvent, tooltip events) fires every frame for every slot. If it throws, it will spam thousands of errors per second. Always add a `boolean broken` circuit breaker that disables the feature after the first failure.

### Lazy class loading
Wynntils classes may not be available at mod init time. Use lazy loading (first access in event handler) rather than static initializers. See `TradeMarketSortHelper` for the pattern.

### Thread safety
- Wynntils events fire on the Render thread
- Background threads (scheduled executors, HTTP callbacks) cannot safely call Wynntils APIs
- Pre-load classes on the main thread if background threads will reference them

### CappedValue
`Models.Lootrun.getChallenges()` returns `CappedValue` with `.current()` and `.max()`. Always null-check.

## Wynntils JAR Structure Overview

Top-level package layout of the Wynntils 3.4.5 JAR (`com/wynntils/`):

```
com/wynntils/
+-- core/          WynntilsMod, Models, Services, Managers (entry points)
+-- models/        Item, Gear, Lootrun, TradeMarket, Stats, Containers, etc.
+-- mc/event/      160+ Minecraft event wrappers (SlotRenderEvent, ScreenOpenedEvent, etc.)
+-- services/      ItemWeight, ItemFilter, and other higher-level services
+-- handlers/      Chat, Container, Entity, Item (event handler infrastructure)
+-- screens/       TradeMarket screens, base widgets (ItemSearchWidget)
+-- utils/         CappedValue, Pair, RangedValue, and other utilities
```

## Complete Wynntils Import Catalog

All unique `com.wynntils.*` imports used across the WynnSort codebase, sorted alphabetically:

```
com.wynntils.core.WynntilsMod
com.wynntils.core.components.Models
com.wynntils.handlers.chat.event.ChatMessageEvent
com.wynntils.mc.event.ContainerRenderEvent
com.wynntils.mc.event.ContainerSetContentEvent
com.wynntils.mc.event.ContainerSetSlotEvent
com.wynntils.mc.event.ItemTooltipRenderEvent
com.wynntils.mc.event.ScreenClosedEvent
com.wynntils.mc.event.ScreenOpenedEvent
com.wynntils.mc.event.SlotRenderEvent
com.wynntils.models.containers.Container
com.wynntils.models.gear.type.GearInfo
com.wynntils.models.gear.type.GearInstance
com.wynntils.models.gear.type.GearTier
com.wynntils.models.items.WynnItem
com.wynntils.models.items.items.game.EmeraldItem
com.wynntils.models.items.items.game.GameItem
com.wynntils.models.items.items.game.GearItem
com.wynntils.models.items.items.game.IngredientItem
com.wynntils.models.lootrun.beacons.LootrunBeaconKind
com.wynntils.models.lootrun.event.LootrunFinishedEvent
com.wynntils.models.lootrun.type.LootrunningState
com.wynntils.models.stats.StatCalculator
com.wynntils.models.stats.type.StatActualValue
com.wynntils.models.stats.type.StatPossibleValues
com.wynntils.models.stats.type.StatType
com.wynntils.models.trademarket.event.TradeMarketStateEvent
com.wynntils.models.trademarket.type.TradeMarketPriceInfo
com.wynntils.models.trademarket.type.TradeMarketState
com.wynntils.screens.base.widgets.ItemSearchWidget
com.wynntils.screens.trademarket.TradeMarketSearchResultScreen
com.wynntils.utils.type.CappedValue
com.wynntils.utils.type.Pair
```

Also uses `net.neoforged.bus.api.SubscribeEvent` (bundled by Wynntils at runtime).
