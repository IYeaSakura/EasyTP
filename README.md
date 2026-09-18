# EasyTP

<div align="center">

[![PaperMC](https://img.shields.io/badge/PaperMC-26.1.2--26.2-004ee9?logo=minecraft&logoColor=white)](https://papermc.io/)
[![Purpur](https://img.shields.io/badge/Purpur-26.1.2--26.2-9b59b6)](https://purpurmc.org/)
[![Java](https://img.shields.io/badge/Java-25-e76f00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36?logo=apache-maven)](https://maven.apache.org/)
[![Adventure](https://img.shields.io/badge/Adventure-MiniMessage-00bfa5?logo=bookstack)](https://docs.advntr.dev/minimessage/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

A lightweight teleport plugin for PaperMC and Purpur 26.1.2 – 26.2 providing random teleport, TPA requests, and multi-home management. All teleports use a configurable delayed countdown with movement and damage cancellation, particle effects, and MiniMessage-formatted chat output.

[Features](#features) | [Tech Stack](#tech-stack) | [Project Structure](#project-structure) | [Getting Started](#getting-started) | [Development](#development) | [Build & Deployment](#build--deployment) | [Configuration](#configuration) | [Commands & Permissions](#commands--permissions) | [Core Design](#core-design) | [Troubleshooting](#troubleshooting) | [Contributing](#contributing) | [License](#license)

---

## Features

### Random Teleport
- **Standard RTP**: Teleport to a random safe ground location within a configurable radius.
- **Spiral Sampling**: Coordinates come from a deterministic golden-angle spiral over weighted ring zones, so searching never degenerates into retry loops over oceans or lava lakes.
- **Async Validation**: Candidate chunks are inspected off the main thread through `ChunkSnapshot`s, then teleported synchronously.
- **Spatial Memory**: Cells already known to be safe or unsafe are skipped without loading a chunk, held in a bounded LRU cache and persisted to SQLite.
- **Preload Pool**: Validated locations are pooled ahead of time in hot/cold/candidate tiers, so `/rtp` usually resolves instantly.
- **Structure RTP (deprecated)**: `/rtp structure <structure>` is disabled by default behind `rtp.structure.enabled` and is slated for removal.
- **World Border Aware**: Out-of-border coordinates are rejected before any validation work.
- **Unsafe Block Filter**: Configurable list of blocks considered unsafe for landing.
- **Liquid Avoidance**: Optionally disallow teleportation onto water, lava, kelp, and bubble columns.

### TPA Requests
- **/tpa <player>**: Request to teleport to another player.
- **/tphere <player>**: Request another player to teleport to you.
- **/tpaccept**: Accept a pending request with a single click or command.
- **/tpdeny**: Deny a pending request.
- **Clickable Messages**: Accept and deny buttons are embedded in chat with hover and click actions.
- **Request Timeout**: Pending requests expire automatically after the configured timeout.

### Homes
- **/sethome [name]**: Save the current location as a named home.
- **/home [name]**: Teleport to a saved home.
- **/delhome [name]**: Delete a saved home.
- **/homelist**: Open a paginated chest GUI listing every home.
- **Home GUI**: Left-click to teleport, shift + right-click to delete, right-click to edit; the edit menu can reset a home to your current position or rename it through chat. Icons are picked from the home's dimension (grass block, netherrack, end stone).
- **Multiple Homes**: Configurable maximum number of homes per player.
- **Persistent Storage**: Homes are stored in a built-in SQLite database (`plugins/EasyTP/data.db`). An existing `homes.yml` is imported on first start and renamed to `homes.yml.migrated`.

### Delayed Teleport
- **Countdown Timer**: Configurable delay before teleportation completes.
- **Title Countdown**: Optional on-screen title showing remaining seconds.
- **Movement Cancellation**: Moving cancels the pending teleport.
- **Damage Cancellation**: Taking damage cancels the pending teleport.
- **Particle Effects**: Portal and enchant particles during countdown; end rod and villager particles on arrival.

### Cooldowns & Permissions
- **Per-Command Cooldowns**: Every command has its own `commands.<command>.cooldown`, counted **separately for each player** and never shared between players. `0` disables the cooldown.
- **Per-Command Delays**: Countdown length is configured per command under `commands.<command>.delay`.
- **Command Classes**: Commands are grouped into three classes, each with its own on/off switch:

  | Class | Switch | Commands |
  |-------|--------|----------|
  | RTP | `commands.rtp.enable` | `/rtp` |
  | Player teleports | `commands.player-teleport.enable` | `/tpa`, `/tphere`, `/tpaccept`, `/tpdeny` |
  | Homes | `commands.home.enable` | `/home`, `/sethome`, `/homelist`, `/delhome` |

  Disabling a class **unregisters** its commands, so players cannot see or run them. Changing a
  switch requires a server restart.
- **Admin Bypass**: `easytp.admin.bypass-cooldown` allows operators to skip cooldowns.

### Dimension Control

Two independent settings decide where teleports may happen — one is about **where a command may be
used**, the other about **where it may send the player**:

- **Per-Command Whitelist**: `dimensions.<command>` limits a command to a set of dimensions. Each
  command has its own list (`rtp`, `home`, `sethome`, `delhome`, `tpa`, `tphere`, `tpaccept`,
  `tpdeny`). A missing or empty list means **no restriction**, which is the default.
- **Cross-Dimension Toggle**: with `teleport.allow-cross-dimension: false`, any teleport that would
  move a player into another dimension is refused and both dimensions are named in the message. This
  covers `/home`, `/tpa`, `/tphere`, accepting a request, and teleporting from the `/homelist` GUI.
  `/rtp` is unaffected — it only ever searches the player's current world.
- **Dimension-Aware Home Icons**: `/homelist` renders each home as a grass block (Overworld),
  netherrack (Nether) or end stone (The End), and the item lore names the dimension.

#### How `/homelist` is gated

`/homelist` is a **view**, so it is deliberately not dimension-gated: it opens in every dimension.
Only switching off the whole home command class (`commands.home.enable: false`) disables it. What it
can *do* is gated per action, each following its equivalent command:

| Action in the GUI | Governed by |
|-------------------|-------------|
| Left-click a home (teleport) | `dimensions.home` + `teleport.allow-cross-dimension` |
| Edit menu → reset coordinates | `dimensions.sethome` — the home would move to your current dimension |
| Shift + right-click (delete) | `dimensions.delhome` |
| Edit menu → rename | not dimension-gated — renaming never moves a home |

So with `sethome` excluding `THE_END`, a home cannot be relocated while you stand in the End; and
with `teleport.allow-cross-dimension: false` you cannot reach a home in another dimension from the
GUI, even though you can still browse the list.

### Localization
- **Language Files**: Player-facing text lives in `lang/messages_en.yml` and `lang/messages_zh.yml`, selected by the `language` setting.
- **Fallback**: Any key missing from the selected locale falls back to the bundled English file.

### Administration
- **Hot Reload**: `/easytp reload` re-applies `config.yml` and the language files without restarting the server (permission `easytp.admin.reload`, default `op`).

---

## Tech Stack

### Core Technologies

| Category | Technology | Version |
|----------|------------|---------|
| Platform | PaperMC / Purpur | 26.1.2 – 26.2 |
| Language | Java | 25 |
| Build Tool | Maven | 3.9+ |
| Core API | `io.papermc.paper:paper-api` | 26.1.2.build.72-stable (pinned) |
| Text Formatting | Adventure / MiniMessage | Provided by Paper |

### Supported Platforms

| Server | Status | Notes |
|--------|--------|-------|
| **PaperMC 26.1.2 – 26.2** | ✅ Supported | `api-version: '26.1.2'` means "26.1.2 or newer", so one JAR runs on the whole supported 26.x line. |
| **PurpurMC 26.1.2 – 26.2** | ✅ Supported | Purpur is a drop-in Paper replacement. EasyTP imports nothing from `org.purpurmc` and uses no NMS, so it behaves identically. |
| **Folia** | ❌ Not supported | Folia refuses to load the plugin (`folia-supported` is not declared). Making it work requires migrating every scheduler call to the region schedulers — see [Folia Compatibility](#folia-compatibility) below. |

The build compiles against the **oldest** API version it claims (`26.1.2`), which guarantees it
cannot accidentally use API that only exists in a newer release. It is additionally verified to
compile against `26.2` stable, confirming no API it relies on was removed in 26.2 (notably the
Adventure 5 changes).

---

## Project Structure

```
EasyTP/
├── pom.xml                                # Maven build configuration
├── LICENSE                                # MIT license
├── README.md                              # English documentation
├── README_zh.md                           # Chinese documentation
├── AGENTS.md                              # Agent-focused development guide
└── src/
    ├── main/
    │   ├── java/net/sakurain/mc/easytp/
    │   │   ├── EasyTPPlugin.java          # Plugin entry point
    │   │   ├── command/                   # One executor per command, plus tab completers
    │   │   ├── gui/                       # Home list/edit inventories, icons, click routing
    │   │   ├── listener/
    │   │   │   └── PlayerListener.java    # Movement, damage, and quit handling
    │   │   ├── manager/
    │   │   │   └── TeleportManager.java   # Cooldowns, delayed teleports, TPA, homes, effects
    │   │   ├── rtp/                       # Random teleport engine
    │   │   │   ├── RtpEngine.java         # Pool scheduling, async validation, safety checks
    │   │   │   ├── RtpStorage.java        # Write-behind persistence for RTP state
    │   │   │   ├── SearchParams.java      # Config-resolved search parameters
    │   │   │   ├── memory/                # Spatial memory cache and cell states
    │   │   │   ├── pool/                  # Hot / cold / candidate preload pool
    │   │   │   ├── scheduler/             # Threading abstraction (Bukkit implementation)
    │   │   │   └── spiral/                # Spiral coordinate generator and ring zones
    │   │   ├── storage/                   # SQLite connection, home repository, home records
    │   │   └── util/
    │   │       └── MessageUtil.java       # MiniMessage loading and sending helpers
    │   └── resources/
    │       ├── plugin.yml                 # Plugin metadata and permissions
    │       ├── config.yml                 # Default configuration
    │       └── lang/                      # messages_en.yml, messages_zh.yml
    └── test/                              # No tests currently (reserved)
```

---

## Getting Started

### Prerequisites

- **Server**: PaperMC or Purpur 26.1.2 – 26.2
- **Java**: OpenJDK 25 or compatible
- **Build Tool**: Maven 3.9+ (only if building from source)

### Installation

```bash
# 1. Build the plugin JAR
cd EasyTP
mvn clean package

# 2. Copy the artifact to the server plugins directory
cp target/easytp-1.0.0-SNAPSHOT.jar /path/to/server/plugins/

# 3. Start or restart the Paper server
```

On first startup, the plugin generates:

- `plugins/EasyTP/config.yml`
- `plugins/EasyTP/data.db` (SQLite; homes and RTP spatial memory)
- `plugins/EasyTP/lang/messages_<locale>.yml`

---

## Development

### Build Commands

| Command | Description |
|---------|-------------|
| `mvn clean package` | Build the plugin JAR |
| `mvn -o clean package` | Build offline (skips remote metadata checks) |

> **There is no test phase.** The project has no automated tests yet — `src/test` does not exist and
> the POM declares no test dependencies. `mvn clean package` compiles and packages only, so
> `-DskipTests` has no effect. All verification is manual on a Paper server.

### Code Style

#### Naming Conventions

| Item | Convention | Example |
|------|------------|---------|
| Classes | PascalCase | `EasyTPPlugin.java`, `TeleportManager.java` |
| Methods | camelCase | `randomTeleport`, `sendRequest` |
| Variables | camelCase | `pendingRequests`, `cooldowns` |
| Constants | SCREAMING_SNAKE_CASE | `MINI_MESSAGE` |
| Packages | lowercase | `net.sakurain.mc.easytp.command` |

### Conventions

- Source identifiers are written in English.
- Player-facing text uses MiniMessage.
- Teleport operations that modify the player run on the main thread.
- Location searching for `/rtp` runs asynchronously, then teleports synchronously.

---

## Build & Deployment

### Production Build

```bash
cd EasyTP
mvn clean package
```

The build produces `target/easytp-1.0.0-SNAPSHOT.jar`.

### Build Stages

| Stage | Description |
|-------|-------------|
| 1. Compile | Compile Java 25 sources |
| 2. Package | Produce the plugin JAR |

There is no test stage: the project currently ships no automated tests.

### Deployment

1. Copy `target/easytp-1.0.0-SNAPSHOT.jar` into the Paper server's `plugins/` directory.
2. Start or restart the server.
3. Edit `plugins/EasyTP/config.yml` to customize messages, RTP ranges, and cooldowns.

---

## Configuration

All configuration is located in `plugins/EasyTP/config.yml`.

### Default Sections

| Section | Description |
|---------|-------------|
| `language` | Locale to load: `en` or `zh` |
| `database` | SQLite file name |
| `commands` | Per-class switches and per-command cooldown / delay |
| `rtp` | Spiral sampling, spatial memory, preload pool, safety options |
| `home` | Maximum homes per player |
| `tpa` | TPA switch and request timeout |
| `effects` | Particles and sounds |

### Configuration Reference

The authoritative defaults live in [`src/main/resources/config.yml`](src/main/resources/config.yml);
the generated `plugins/EasyTP/config.yml` mirrors it. Key settings:

| Key | Default | Description |
|-----|---------|-------------|
| `language` | `zh` | Locale file to load |
| `database.file` | `data.db` | SQLite file, relative to `plugins/EasyTP/` |
| `commands.<class>.enable` | `true` | Class switch: `rtp`, `player-teleport`, `home`. Off = commands unregistered |
| `commands.<command>.cooldown` | `rtp` 15, `tpa`/`tphere` 15, others 0 | Cooldown in seconds, counted per player |
| `commands.<command>.delay` | `rtp`/`tpa`/`tphere` 5, `home` 3 | Countdown in seconds |
| `rtp.enabled` | `true` | Master switch for `/rtp` |
| `rtp.min-radius` / `max-radius` | `2000` / `5000` | Fallback band, used only when `ring-zones` is absent |
| `rtp.overworld-surface-only` | `true` | Require open sky above the landing spot in the Overworld |
| `rtp.max-scan-depth` | `16` | Blocks scanned downward from the surface |
| `rtp.spiral.grid-spacing` | `16` | Average spacing between spiral samples |
| `rtp.spiral.ring-zones` | near/mid/far | Weighted distance bands with relative weights |
| `rtp.spatial-memory.cell-size` | `32` | Spatial memory cell size in blocks |
| `rtp.spatial-memory.max-entries` | `50000` | In-memory cell cache cap (least-recently-used eviction) |
| `rtp.pool.base-size` | `12` | Preload pool size before the player-count term |
| `rtp.pool.size-multiplier` | `2.0` | Extra pooled locations per online player |
| `rtp.pool.max-validations-per-tick` | `2` | Candidate validations per tick |
| `rtp.pool.max-chunk-loads-per-tick` | `2` | Async chunk loads per tick |
| `rtp.pool.max-in-flight-loads` | `8` | Hard cap on simultaneous chunk loads (the real backpressure) |
| `rtp.pool.player-wait-timeout-seconds` | `15` | How long a queued player waits for the pool |
| `rtp.pool.max-queued-players` | `10` | Queue cap while the pool refills |
| `rtp.biome-blacklist` | oceans, rivers | Biomes skipped in the Overworld |
| `rtp.allow-liquid` | `false` | Allow landing on liquids |
| `rtp.unsafe-blocks` | lava, cactus, fire, … | Blocks that are never a safe floor |
| `rtp.structure.max-safe-distance` | `256` | Search radius around a target structure |
| `rtp.structure.enabled` | `false` | Deprecated structure RTP. Off by default |
| `home.max-homes` | `5` | Homes per player |
| `tpa.enabled` | `true` | Master switch for TPA requests |
| `tpa.timeout` | `30` | Seconds before a pending request expires |
| `teleport.show-title` | `true` | On-screen title countdown |
| `teleport.allow-cross-dimension` | `true` | Allow teleports that move a player to another dimension |
| `dimensions.<command>` | all dimensions | Dimensions a command may be used from |
| `effects.enabled` | `true` | Teleport particles and sounds |
| `debug.enabled` | `false` | Verbose `[DEBUG]` console diagnostics |
| `debug.summary-interval-seconds` | `30` | How often the RTP pipeline summary is printed |

Messages are **not** stored in `config.yml`; they live in the language files described under
[Localization](#localization).

---

## Commands & Permissions

### Player Commands

| Command | Permission | Default | Description |
|---------|------------|---------|-------------|
| `/rtp` | `easytp.command.rtp` | `true` | Random teleport to a safe location |
| `/tpa <player>` | `easytp.command.tpa` | `true` | Request to teleport to a player |
| `/tphere <player>` | `easytp.command.tphere` | `true` | Request a player to teleport to you |
| `/tpaccept` | `easytp.command.tpaccept` | `true` | Accept a pending TPA request |
| `/tpdeny` | `easytp.command.tpdeny` | `true` | Deny a pending TPA request |
| `/sethome [name]` | `easytp.command.sethome` | `true` | Set a named home (defaults to `home`) |
| `/home [name]` | `easytp.command.home` | `true` | Teleport to a named home |
| `/delhome [name]` | `easytp.command.delhome` | `true` | Delete a named home |
| `/homelist` | `easytp.command.homelist` | `true` | Open the home management GUI |

> `/rtp structure <structure>` is **deprecated and disabled by default**. It can be re-enabled with
> `rtp.structure.enabled: true` and is planned for removal.

See [Admin Commands](#admin-commands) for `/easytp reload`.

### Admin Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `easytp.admin.bypass-cooldown` | `op` | Bypass all teleport cooldowns |
| `easytp.admin.reload` | `op` | Use `/easytp reload` |

### Admin Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/easytp reload` | `easytp.admin.reload` | Re-read `config.yml` and the language files |

### Hot Reload

`/easytp reload` re-reads `config.yml` and the language files without restarting the server.

- **Applied immediately**: messages and `language`; every `commands.<command>.cooldown` / `.delay`;
  `home.max-homes`; `tpa.*`; `rtp.enabled`; `teleport.show-title`; `effects.enabled`; and the
  `rtp.spiral`, `rtp.spatial-memory`, `rtp.pool`, safety, and structure sections.
- **Needs a restart**: `commands.<class>.enable` (commands are registered once during startup) and
  `database.file` (the SQLite connection is already open). The reload detects these and tells you.
- **Fails safe**: the file is parsed before anything is applied. If `config.yml` is not valid YAML
  the reload is aborted and the running configuration keeps working — otherwise every setting would
  silently fall back to its default.

---

## Core Design

### Random Teleport Pipeline

The preloader is deliberately lazy: it does **nothing while no players are online**, and its chunk
loads are capped rather than merely rate-limited.

1. **Generate** — a background task samples coordinates from a golden-angle spiral over weighted ring zones. The spiral index is persisted per world and ring, so one full cycle never repeats a point. Generation is **self-limiting**: it counts everything already pooled or queued and stops once the target (`pool.base-size + online × pool.size-multiplier`) is met.
2. **Skip known cells** — each coordinate maps to a spatial-memory cell. Cells already recorded as unsafe are discarded without loading a chunk.
3. **Reject out-of-border** — coordinates outside the world border are dropped before any validation work.
4. **Validate** — the candidate chunk is loaded asynchronously and inspected through a `ChunkSnapshot`, so no chunk access happens on the main thread. Loads are limited to `pool.max-validations-per-tick` per tick **and** to `pool.max-in-flight-loads` outstanding at once. The in-flight cap is the important one: without it, a slow terrain generation lets pending chunk-IO requests pile up without bound.
5. **Classify** — the column is graded per environment:
   - **Overworld**: scan downward from the highest non-air block, up to `max-scan-depth`. The floor must be solid and not in `unsafe-blocks`, foot and head must be passable, and (with `overworld-surface-only`) the spot needs open sky.
   - **Nether / The End**: scan downward from below the ceiling, skipping bedrock and liquid surfaces.
6. **Pool or discard** — safe spots enter the preload pool (hot when their chunk is loaded, cold otherwise); failures are written to spatial memory so the cell is never re-checked from disk.
7. **Serve** — `/rtp` takes a ready location and starts the delayed teleport. When the pool is empty the player is queued briefly while candidates are generated on demand. A pooled location whose chunk has since unloaded is reloaded, and if it turns out to be unsafe the player falls back to a freshly generated location instead of failing outright.

### Home GUI

- `/homelist` opens a 54-slot inventory paged 45 homes at a time.
- Icons are chosen per world environment (grass block, netherrack, end stone).
- Left-click teleports, shift + right-click deletes, right-click opens the edit menu.
- The edit menu can reset a home to the player's current position or start a rename, which consumes the next chat message.

### TPA Flow

1. The requester sends a request to a target player.
2. The target receives a clickable chat message with Accept and Deny buttons.
3. If accepted, the correct player is teleported after the configured delay.
4. If denied or timed out, both parties are notified and no teleport occurs.

### Delayed Teleport Flow

1. A countdown task starts, displaying titles and spawning particles each second.
2. If the player moves or takes damage, the task is cancelled.
3. When the countdown reaches zero, the player is teleported asynchronously.
4. Arrival particles and completion messages are shown on the main thread.

---

## Folia Compatibility

**EasyTP does not currently support Folia.** It does not declare `folia-supported: true`, so Folia
refuses to load the plugin cleanly instead of loading it and failing at runtime.

Folia removes the main thread: each world is split into independently ticking regions, and server
API may only be touched from the thread that owns the relevant region. EasyTP is written for a
single main thread, and these usages are not valid on Folia:

| Area | Blocking usage |
|------|----------------|
| Scheduling | `Bukkit.getScheduler()` in 5 places, plus the countdown task built on `BukkitRunnable` |
| Chunk access | Synchronous `world.getChunkAt(...)` in 2 places |
| Region affinity | `spawnParticle`, `openInventory`, `closeInventory`, `showTitle` and `playSound` must run on the thread owning the player |
| Async safety | `world.getWorldBorder()` is read from the RTP validation thread |

The migration is **mechanical rather than architectural**: the four portable schedulers —
`getGlobalRegionScheduler`, `getRegionScheduler`, `getAsyncScheduler` and `Entity#getScheduler()` —
already ship in the `paper-api` this plugin compiles against, so one JAR can serve Paper, Purpur
**and** Folia with no extra dependency and no separate build.

### Recommendation: one codebase, not a separate Folia edition

Comparable plugins take exactly this route — [LeafRTP](https://www.spigotmc.org/resources/leafrtp-paper-folia-velocity.94812/)
ships a single artifact for Paper, Folia *and* Velocity, and PaperMC maintains an official
[Supporting Paper and Folia](https://docs.papermc.io/paper/dev/folia-support/) guide for it.
`RtpScheduler` was already written as the seam for this change, so the architecture is prepared.

One caveat worth planning for: Folia is genuinely *faster* at random teleport, because region
threads parallelise a search that Paper serialises onto one thread. But `/rtp`'s habit of preloading
chunks thousands of blocks away across dimensions is far more expensive under regionisation — on
Folia, shrink `rtp.spiral.ring-zones`. That is a configuration concern, not a reason to fork.

---

## Troubleshooting

### RTP Always Fails

**Problem**: `/rtp` repeatedly reports that no safe location was found.

**Solution**:
- Confirm the world border surrounds the configured ring zones — samples outside it are rejected before validation.
- Reduce the `rtp.spiral.ring-zones` radii, or relax `rtp.biome-blacklist` / `rtp.unsafe-blocks` if they are too strict.
- Raise `rtp.spatial-memory.max-entries` if `/rtp` keeps re-checking the same regions (evicted cells are re-read from SQLite).
- For Nether or End worlds, verify the world border is large enough.
- Check the server log: failed async chunk loads are logged and surface to the player as `rtp-failed`.

### TPA Request Not Received

**Problem**: The target player does not see the request message.

**Solution**:
- Ensure `tpa.enabled` is `true`.
- Confirm the target is online and has not already blocked chat messages.
- Check that the request has not expired.

### Home Teleport Fails

**Problem**: `/home` reports that the home is not set or the world is unloaded.

**Solution**:
- Verify the home name with `/homelist`.
- Ensure the home's world is loaded.
- Check `home.max-homes` if setting a new home fails.

### Build Fails

**Problem**: `UnsupportedClassVersionError` during build.

**Solution**: Install JDK 25 and set `JAVA_HOME` accordingly.

```bash
java -version
# Expected: openjdk version "25" or higher
```

### Diagnosing With Debug Logging

Set `debug.enabled: true` and run `/easytp reload` to make EasyTP describe what it is doing. Every
line is prefixed `[DEBUG][category]` so it can be grepped out of the server log; the categories are
`rtp`, `storage`, `teleport`, `cooldown` and `reload`.

The `rtp` category additionally prints a summary every `debug.summary-interval-seconds`, **including
while nobody is online** — which is the situation where these problems are hardest to notice:

```
[DEBUG][rtp] ---- RTP summary over 30s ----
[DEBUG][rtp] chunk loads: requested=36 ok=36 failed=0 slow(>=500ms)=4 slowest=1840ms
[DEBUG][rtp] throttle: in-flight=0/8 backpressureSkips=12 idleSkips=0
[DEBUG][rtp] pipeline: generated=36 borderRejected=0 memoryRejected=8 validated=28 unsafe=8
[DEBUG][rtp] serve: hot=0 cold=1 queued=0 queueRejected=0 waitTimeouts=0 staleFallbacks=0
[DEBUG][rtp] spatial memory: hits=214 misses=52 cachedCells=266
[DEBUG][rtp] storage: cellsFlushed=44 spiralFlushed=2 failures=0 pendingCells=0
[DEBUG][rtp] pool[world]: hot=12 cold=2 candidates=0 waiting=0 chunksStillLoaded=14/14
```

How to read it:

| Symptom | Meaning |
|---------|---------|
| `idleSkips=0` while nobody is online, with `requested` climbing every summary | The preloader is not resting — this is the runaway that used to exhaust the heap |
| `slow` / `slowest` consistently high | Terrain generation cannot keep up; lower `rtp.pool.max-in-flight-loads` |
| `backpressureSkips` comparable to `requested` | The in-flight cap is doing its job |
| `chunksStillLoaded` staying near the pool size while the server is idle | Those probed chunks are being pinned in memory |
| `misses` far above `hits` with a low `cachedCells` | `rtp.spatial-memory.max-entries` is too small and the LRU is thrashing |
| `pendingCells` growing | Storage flushes are failing or the database cannot keep up |
| `waitTimeouts` above zero | Players are requesting `/rtp` faster than the pool can supply |

Leave debug **off** in production: it is verbose, and every chunk load emits its own line.

---

## Contributing

Contributions are welcome. Please follow this workflow:

1. Fork the repository.
2. Create a feature branch: `git checkout -b feature/your-feature`.
3. Make changes following the code style guidelines.
4. Build and test locally: `mvn clean package`.
5. Commit: `git commit -m 'feat: add new feature'`.
6. Push: `git push origin feature/your-feature`.
7. Open a Pull Request.

### Code Quality Requirements

Before submitting a PR:

- [ ] Build succeeds (`mvn clean package`)
- [ ] Code follows project naming conventions
- [ ] No compiler warnings introduced
- [ ] Player teleport operations remain on the main thread
- [ ] New messages use MiniMessage and support placeholders

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) file for details.

```
MIT License

Copyright (c) 2026 Yuyang.Wang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Contact

- **Author**: Yuyang.Wang
- **Website**: [https://sakurain.net](https://sakurain.net)
- **Email**: [Yae_SakuRain@outlook.com](mailto:Yae_SakuRain@outlook.com)
- **GitHub**: [https://github.com/IYeaSakura](https://github.com/IYeaSakura)

---

<p align="center">
  Made by Yuyang.Wang
</p>
