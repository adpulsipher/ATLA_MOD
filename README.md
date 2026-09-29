# ATLA Story Mods (Forge 1.20.1)

Two small Forge mods that turn the **KyoshiCraft** world into a story-driven *Avatar: The Last Airbender*
campaign. The player is Aang, locked in Adventure Mode. The mods control **which bending elements
Aang has**, **what he can bend in the world**, and **where he is allowed to go** while parts of the map
are still unfinished.

| Mod | Jar | What it does |
|-----|-----|--------------|
| **ATLA Core: Bending Progression** (`atla_core`) | `atla_core-1.20.1-1.0.0.jar` | Per-player elements (Air/Water/Earth/Fire), element levels, the currently selected element (HUD + key `R`), story flags and **narrative gates** that unlock elements. Commands + API for quest mods. |
| **ATLA Gates: Bending Overrides & Boundaries** (`atla_gates`) | `atla_gates-1.20.1-1.0.0.jar` | `config/schematic_overrides.json`: right-click designated blocks with the right element/level to swap blocks (WorldEdit `.schem`, fill/replace, setblock). `config/story_boundaries.json`: keeps players out of unfinished areas until the story opens them. Requires `atla_core`. |

Both jars go in `mods/`. Nothing else is required. Optional extras:

* **WorldEdit** (only for *authoring* schematics; the mods read `.schem` files themselves).
* **Game Stages** (if installed, requirements can check stages and element levels are mirrored as
  stages like `atla_water_2`, so FTB Quests can gate quests on them).
* A quest mod (FTB Quests, Heracles, ...) and an NPC/dialogue mod. These mods don't try to
  replace them; quests talk to the story through the `/atla` commands.

---

## How the pieces fit

```
 quest / NPC mod ──(reward: /atla flag add @s book1.northern_water_tribe)──┐
 command blocks  ──(/atla flag add, /tag, scoreboards)─────────────────────┤
 advancements    ──────────────────────────────────────────────────────────┤
                                                                            ▼
                        ┌───────────── atla_core ─────────────┐
                        │ story flags ─► narrative gates ─►   │
                        │ unlock / level up elements          │──► HUD (active element + level)
                        └───────────────┬─────────────────────┘
                                        │ AtlaApi (level, active element, flags, Requirement)
                        ┌───────────────▼─────────────────────┐
                        │ atla_gates                          │
                        │  right-click block ─► check element │──► paste .schem / fill / replace
                        │  player tick ─► boundary zones      │──► push back / set flags on enter
                        └─────────────────────────────────────┘
```

* A new player starts with **only Airbending** (level 1). Water, Earth and Fire are at level 0:
  they can't be selected, fail every bending check and don't show on the HUD.
* They unlock only when a **narrative gate** in `config/story_progression.json` clears. A gate
  clears once per player when its `requires` pass: usually a story flag set by a quest reward.
* The player switches the active element with **R** (rebindable; there are also optional
  "previous" and "select air/water/earth/fire" keys). Block overrides check the *active* element.

---

## 1. `config/story_progression.json` (atla_core)

Generated on first launch with a commented example story. Comments (`//`, `#`) and trailing
commas are allowed. Reload with `/atla reload` (or vanilla `/reload`).

```jsonc
{
  "elements": {
    "air":   { "startUnlocked": true,  "startLevel": 1, "maxLevel": 5 },
    "water": { "startUnlocked": false, "startLevel": 1, "maxLevel": 5 },
    "earth": { "startUnlocked": false, "maxLevel": 5 },
    "fire":  { "startUnlocked": false, "maxLevel": 5,
               "unlockAnnouncement": { "title": "&cFirebending", "subtitle": "The dragons' fire is life, not destruction" } }
  },
  "levelNames": ["Novice", "Apprentice", "Adept", "Expert", "Master"],
  "settings": {
    "evaluateEveryTicks": 20,             // re-check gates for tags/scores/stages
    "autoSelectUnlockedElement": true,
    "allowPlayerElementSwitching": true,  // false during scripted scenes
    "announceUnlocks": true, "announceLevelUps": true,
    "gameStages": { "mirrorElements": true, "mirrorFlags": false, "prefix": "atla_" }
  },
  "gates": [
    {
      "id": "waterbending_awakens",
      "description": "Book 1: Katara and Aang learn together at the Northern Water Tribe",
      "requires": { "flags": ["book1.northern_water_tribe"] },
      "unlock": ["water"],                 // level 0 -> startLevel
      "levels": { "air": 2 },              // raise to at least this level
      "addFlags": [], "removeFlags": [],
      "commands": ["give @s minecraft:potion"],   // run as the player with op permission
      "announce": { "title": "...", "subtitle": "...", "actionbar": "...", "message": "...", "sound": "..." }
    }
  ]
}
```

### Requirements (used everywhere: gates, overrides, zones)

All listed checks must pass. Every key is optional.

| Key | Meaning |
|-----|---------|
| `flags` / `anyFlags` / `notFlags` | story flags: all of / at least one of / none of |
| `elements` | `{"earth": 2}` element unlocked at level ≥ 2 (or a list `["water"]` = level ≥ 1) |
| `activeElement` | the element currently selected on the HUD |
| `gates` | narrative gates already cleared |
| `advancements` | vanilla/datapack advancements completed (`"kyoshicraft:story/kyoshi_island"`) |
| `tags` / `notTags` | vanilla entity tags (`/tag @p add reached_omashu`), great for command blocks |
| `stages` | Game Stages (only if that mod is installed) |
| `scores` | `{"chapter": {"min": 3, "max": 5}}` or `{"chapter": 3}` (= min) |
| `any` | `[ {requirement}, {requirement} ]`: at least one must pass |

Text fields accept plain text with `&` colour codes (`"&bKatara&r: Hi!"`) or a JSON text component
(`{"text":"Katara","color":"aqua"}`). Placeholders: `{player}`, and `{element}`, `{level}`,
`{levelName}` where they make sense.

---

## 2. `config/schematic_overrides.json` (atla_gates)

When a player in **Adventure** (or Survival) right-clicks a trigger block:

1. Is the element **unlocked**? No → *"Only Earthbending could move this... and you cannot bend it yet."*
2. Is its **level** ≥ `minLevel`? No → *"Your Earthbending is not strong enough yet (needs Adept)."*
3. Is it the **active** element (unless `"requireActiveElement": false`)? No → *"Switch to Earthbending to bend this."*
4. Do the extra `requires` pass? No → *"The time isn't right for this yet."*
5. Otherwise the actions run, animated, with element-themed sound and particles; the click is consumed.

Every message can be overridden per entry. One-shot overrides (`"once": true`, the default) are
remembered in the world save, and the changed blocks are simply part of the world from then on.

```jsonc
{
  "settings": {
    "gameModes": ["adventure", "survival"],     // creative builders don't trigger anything
    "messageCooldownTicks": 20,
    "failSound": null,
    "recordUndo": true,                          // enables /atla override reset <id>
    "animation": { "order": "outward", "blocksPerTick": 48 }
  },
  "overrides": [
    {
      "id": "kyoshi_island_rock_wall",
      "dimension": "minecraft:overworld",
      "trigger": [[120, 70, -340], [121, 70, -340]],        // or one [x,y,z], or "triggerArea": {"from":..,"to":..}
      "element": "earth",                                   // air | water | earth | fire | any
      "minLevel": 2,
      "requireActiveElement": true,
      "requires": { "flags": ["book2.toph_joins"] },        // optional extra conditions
      "once": true,
      "action": { "type": "schematic", "file": "rock_wall_open" },
      "animation": { "order": "top_down", "blocksPerTick": 24 },  // instant | outward | top_down | bottom_up | random
      "effects": { "sound": "minecraft:block.stone.break", "particle": "minecraft:cloud",
                   "particleChance": 0.3, "breakParticles": true },
      "setFlags": ["opened.rock_wall"],
      "commands": [],
      "messages": { "success": "&aThe wall sinks into the earth.", "locked": "...", "levelTooLow": "...",
                    "wrongElement": "...", "notReady": "...", "alreadyDone": "..." },
      "updateNeighbors": false
    }
  ]
}
```

### Actions

Use `"action": {...}` or `"actions": [ ... ]` (applied in order; later ones win on the same block).

| type | fields | notes |
|------|--------|-------|
| `schematic` | `file`, optional `origin` **or** `position`, `includeAir` (true), `includeBlockEntities` (true) | No `origin`/`position` → pastes back **exactly where it was copied** (like `//paste -o`). `origin` = where you'd stand for `//paste`. `position` = minimum corner. |
| `fill` | `from`, `to`, `with`, optional `replace` list | like `/fill ... replace` |
| `replace` | `from`, `to`, `with`, `replace` (required) | e.g. `["#minecraft:ice"]` → `"minecraft:water"` (Firebending melts ice) |
| `clear` | `from`, `to`, optional `replace` | fill with air |
| `blocks` | `blocks: [{"pos": [x,y,z], "state": "minecraft:oak_door[half=lower]", "nbt": "{...}"}]` | exact blocks |

Block matchers accept ids, `#tags` and states with properties (`minecraft:oak_log[axis=y]`).

### Schematics

Supported: **WorldEdit / Sponge `.schem` v1, v2 and v3** (WorldEdit 7.2.x on 1.20.1 writes v2) and vanilla
structure-block **`.nbt`**. Block names from older versions (e.g. a schematic saved in 1.18) are upgraded
with Minecraft's own DataFixer. Chests, signs, banners and other block entities keep their data, and containers
are emptied before being replaced so nothing drops as items. Legacy MCEdit `.schematic` files (numeric
ids) are not supported; re-save them with WorldEdit 7.

Files are looked up by name (extension optional) in:

1. `config/atla_gates/schematics/`
2. `config/schematics/`
3. `config/worldedit/schematics/`, where WorldEdit for Forge saves `//schem save <name>`

**Recommended workflow for a bendable wall**

1. Build the *after* state in place (the wall gone / the ice melted / the bridge frozen).
2. `//pos1`, `//pos2` around it, `//copy`, `//schem save rock_wall_open`.
3. `//undo` (or rebuild the *before* state).
4. `/atla pos` while looking at the block the player should click, which prints a click-to-copy `[x, y, z]`.
5. Add the entry, `/atla reload`, switch to Adventure, test.
6. `/atla override reset rock_wall_open` puts the *before* state back (undo snapshot) so you can test again.

For simple cases you don't need a schematic at all: `replace`/`clear` boxes are enough.

---

## 3. `config/story_boundaries.json` (atla_gates)

```jsonc
{
  "settings": {
    "exemptGameModes": ["creative", "spectator"],
    "messageCooldownTicks": 40,
    "showWalls": true, "wallDistance": 4, "wallParticle": "minecraft:cloud",
    "pushSound": { "id": "minecraft:entity.phantom.flap", "volume": 0.5, "pitch": 0.7 },
    "defaultMessage": "&7Mist rolls in. This part of the world isn't ready for you yet."
  },
  "zones": [
    // playable area for Book 1 (x/z only = full height, so gliders can't fly over it)
    { "id": "book1", "type": "bounds", "from": [-1500, -900], "to": [800, 600] },
    // bigger area once Earthbending is learned: the world grows with the story
    { "id": "book2", "type": "bounds", "from": [-2500, -2000], "to": [2500, 2000],
      "requires": { "gates": ["earthbending_awakens"] } },
    // unfinished region: no requires = closed for good
    { "id": "fire_nation_wip", "type": "barrier", "from": [3000, -1000], "to": [6000, 2000],
      "message": "&cThe Fire Nation is still being built.", "fallback": [2900, 70, 0] },
    // story trigger by location
    { "id": "arrive_omashu", "type": "trigger", "from": [450, 60, -120], "to": [520, 120, -40],
      "setFlags": ["book1.arrived_omashu"], "enterMessage": "&6Omashu", "once": true }
  ]
}
```

* **barrier**: can't be entered unless `requires` passes.
* **bounds**: once any bounds zone in a dimension is open for a player, they must stay inside an open one.
* **trigger**: entering (with `requires` passing) sets flags / runs commands / shows `enterMessage`.

Enforcement runs every tick and puts the player back at their last allowed position (or the
zone's `fallback`, or their spawn), whatever moved them: walking, gliding, boats, pearls or `/tp`.
A faint mist wall appears when they get close to a closed edge. Creative/spectator players are never
restricted, so you can keep building.

---

## Commands

All `/atla` commands tab-complete element ids, gate ids, override ids, zone ids and known flags.

| Command | Who | Purpose |
|---------|-----|---------|
| `/atla flag add\|remove <targets> <flag>` | op / quest rewards | set story flags (gates re-evaluate immediately) |
| `/atla flag has <player> <flag>` | op | returns 1/0 → usable with `/execute store result` |
| `/atla flag list [player]` | op | |
| `/atla element unlock\|lock <targets> <element>` | op | bypass gates (testing / special scenes) |
| `/atla element set <targets> <element> <level>` / `levelup` | op | |
| `/atla element select <element> [targets]` | anyone (self) | switch active element (only unlocked ones) |
| `/atla gate list [player]` / `test <player> <gate>` | op | see which gates are cleared and **why** one isn't |
| `/atla gate clear\|forget <targets> <gate>` / `evaluate <targets>` | op | |
| `/atla progress [player]` | anyone (self) | elements, levels (+ flags/gates for ops) |
| `/atla reset <targets>` | op | wipe story progression (back to Air only) |
| `/atla reload` | op | re-read all three JSON files and report errors in chat |
| `/atla pos` | op | copyable `[x, y, z]` of the looked-at block and your feet |
| `/atla override list\|info <id>\|test <id> [player]` | op | inspect bendable blocks; `test` explains failures |
| `/atla override trigger <id> [player]` | op | fire it without checks |
| `/atla override reset <id>\|all` | op | restore the original blocks and allow it again |
| `/atla zone list\|check [player]` | op | what zones apply at a position |
| `/atla zone reset <targets> <zone>` | op | let a one-shot trigger zone fire again |
| `/atla schematic info <file>` | op | size, palette, paste offset and saved copy position |

### Quest-mod integration

* **FTB Quests**: add a *Command* reward: `/atla flag add {p} book1.northern_water_tribe`
  (or with Game Stages installed, a *Stage* reward + `"stages"` in the gate's requires). Quest tasks can
  check element progress through mirrored stages (`atla_water`, `atla_water_3`, ...).
* **Command blocks / datapacks**: `/atla flag add @p ...`, or use vanilla `/tag` and `scores`
  directly in `requires`.
* **Advancements**: gates re-check whenever the player earns one.

---

## HUD & controls

![Element HUD next to the KyoshiCraft hotbar](docs/hud-preview.png)

* The active element is drawn in a slot next to the hotbar, styled after the KyoshiCraft resource pack's
  hotbar (dark bark-brown slot, riveted corners, bevelled edge), with level pips beside it. It steps
  around the off-hand slot and the hotbar attack indicator automatically.
* `R`: next unlocked element (rebind under *Controls → Avatar: Bending*).
* `config/atla_core-client.toml`: HUD on/off, anchor (`HOTBAR_RIGHT`, `HOTBAR_LEFT`, `TOP_LEFT`,
  `TOP_RIGHT`), pixel offsets, element name popup.

## For other mods (API)

`com.adpulsipher.atla.core.api.AtlaApi`: `getLevel`, `isUnlocked`, `getActiveElement`,
`canBend(player, element, minLevel, mustBeActive)`, `hasFlag`, `addFlag`, `unlock`, `setLevel`, `runCommands` ...
Forge events on `MinecraftForge.EVENT_BUS`: `ElementLevelChangedEvent`, `ActiveElementChangedEvent`,
`StoryFlagChangedEvent`, `NarrativeGateClearedEvent`, `AtlaReloadEvent`. `Requirement` can be reused
to parse and test the same condition JSON in your own configs. A future bending-abilities mod can
simply check `AtlaApi.canBend(...)` before letting a move fire.

---

## Building

Any installed Java works to *start* the build (tested with Java 17, 21 and 25). Minecraft 1.20.1's
build tools only run on Java 17, so `gradle/gradle-daemon-jvm.properties` tells Gradle to download
its own JDK 17 on the first build and use that. You don't need to install or switch anything.

```bash
./gradlew dist                              # both jars -> build/dist/
./gradlew :atla-gates:runClient             # dev client with both mods (+ WorldEdit for authoring)
./gradlew :atla-gates:runGameTestServer     # headless in-game test suite
```

On Windows PowerShell use `.\gradlew dist`. The first build downloads Gradle, a JDK and Minecraft
and takes several minutes; later builds take seconds.

The GameTest suite (`atla-gates/src/gametest`) runs on a real 1.20.1 server and checks:
the Air-only start, gates unlocking elements from flags/tags, gate chaining and level caps; the full
right-click path in Adventure mode (locked → too weak → wrong element → success), animated
clearing, one-shot behaviour and undo; **real WorldEdit 7.2.15 `.schem` files** pasted back in place and at
a shifted origin, with block states and chest contents intact; Sponge v3 files; upgrading 1.16 block
names; palettes needing multi-byte varints; and barrier, bounds and trigger zones.

`tools/make_hud_textures.py` regenerates the HUD textures from the resource pack's palette.
