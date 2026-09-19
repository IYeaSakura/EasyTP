# EasyTP 1.1.0

2026-09-19 · PaperMC / Purpur 26.1.2 – 26.2 · Java 25

本版新增 Folia 支持，并明确声明对 Purpur 的兼容；加入按命令的维度白名单、跨维度传送开关、
维度感知的家列表图标与可点击的 TPA 按钮；同时修掉三处会静默出错的问题。

---

## 新增

**支持 Folia。** `plugin.yml` 声明 `folia-supported: true`。RTP 引擎的线程抽象改为 Paper 与
Folia 共有的四个调度器（`AsyncScheduler`、`GlobalRegionScheduler`、`RegionScheduler`、
`Entity#getScheduler()`），原先的 `runSync` / `runLaterSync` / `runTimerSync` 更名为
`runGlobal` / `runLaterGlobal` / `runTimerGlobal`（语义是"全局区域 tick 线程"，在 Paper 上
即主线程）。涉及玩家的投递改为派发到该玩家自己的区域；`findSafeSpotSync` 与 `fastRevalidate`
这两处同步区块读取在非属主线程上拒绝执行，分别回退到异步路径或按普通搜索失败处理，
而不是冒崩溃的风险。
**同一个 JAR 现在可运行于 Paper、Purpur 和 Folia。**

**按命令的维度白名单。** 新增 `dimensions.<command>`，限制命令**可以在哪个维度使用**。不配置
即不限制；值无效时只警告一次并忽略，不会把命令锁死。默认对 `/rtp`、`/home`、`/sethome`、
`/delhome`、`/tpa`、`/tphere`、`/tpaccept`、`/tpdeny` 开放三个维度。

**跨维度传送开关。** 新增 `teleport.allow-cross-dimension`。设为 `false` 时，`/home`、`/tpa`、
`/tphere` 以及 `/homelist` 界面里的传送在目标维度不同时被拒绝，并明确告知玩家是从哪个维度
到哪个维度。

**家列表图标区分维度。** `/homelist` 的图标方块即维度提示——草方块 / 地狱岩 / 末地石，同时
lore 显示世界、坐标与本地化的维度名；世界未加载时显示"未知（世界未加载）"。

**TPA 请求带可点击按钮。** 请求消息里附带 `[同意]` / `[拒绝]` 按钮，点击即执行
`/tpaccept` / `/tpdeny`，鼠标悬停有说明。

**`/homelist` 明确定位为只读视图。** 它在所有维度都能打开，界面内的每个动作改由对应命令把关：
传送走 `dimensions.home` + 跨维度开关，改点走 `dimensions.sethome`，删除走
`dimensions.delhome`；重命名不涉及移动，因此不受限制。

## 修复

**白名单里的 `OVERWORLD` 一直被当作无效值。** Bukkit 给主世界起的枚举名是 `NORMAL`，而插件
自带的配置与语言文件的命名习惯都写 `overworld`；旧实现直接调
`World.Environment.valueOf`，于是每个 `OVERWORLD` 条目都被丢弃，**该命令在主世界被拒绝**
（默认配置下 `/sethome`、`/home`、`/rtp` 等全部如此）。现在先按玩家习惯的名字解析
（`OVERWORLD` / `WORLD` / `NORMAL`、`END` / `THE_END`、`THE_NETHER`），再回落到枚举名，
写成 `NORMAL` 的旧配置照常工作。同一段代码的另外两处一并修掉：「不限制」原先用一个只含三个
原版维度的固定集合表示，导致自定义维度里的玩家被拒绝所有受管命令；每次判断又会解析两遍白名单，
使同一条警告打印两次。

**环带容量估计把 π 截断成了 3。** `RingZone.area()` 写成 `(long) Math.PI * (...)`，先取整后
相乘，容量估计因此是 246093 而不是 257708（低 4.5%），螺旋会在环带最外约 96 格尚未覆盖时
提前回绕。取整已移到乘法之后。

**落点列扫描里有一段死代码。** `determineUnsafeReason` 有一个 `if`，两个分支返回同一个值；
实际上列中第一个非空气方块就是判定依据，无论它是岩浆、水、基岩还是普通地形。

## 构建与兼容性

- **`paper-api` 固定为 `26.1.2.build.72-stable`**，不再使用范围 `[26.1.2.build,)`。Maven 范围
  会解析到**最高**匹配版本——在本仓库是 26.3 的 alpha——于是插件可能在声明
  `api-version: '26.1.2'` 的同时针对未发布的 API 编译，进而在它声称支持的服务器上抛
  `NoSuchMethodError`。
- 支持范围明确为 PaperMC / Purpur **26.1.2 – 26.2**（Java 25），针对区间两端分别编译，
  并在 Paper 26.2 上实际运行。

## 仓库内容

本版同时首次纳入 RTP 算法的完整设计文档与实测数据：

- `docs/easytp-rtp-algorithm.md` —— `/rtp` 的算法设计（确定性螺旋采样、空间记忆、分级预取与
  背压），含解析推导与 Paper 26.2 上的实测。
- `docs/bench/` —— 实测原始输出；`benchmarks/` —— 直接驱动生产类、可在真实服务端复跑的
  基准插件。

## 升级说明

1. **先删除旧 JAR。** `plugins/` 目录里同时存在两个 EasyTP JAR 会导致插件无法加载；请先移除
   `EasyTP-1.0.0-SNAPSHOT.jar`，再放入 `easytp-1.1.0.jar`。
2. **不需要迁移数据库。** homes 与 RTP 状态沿用原格式。
3. 已有的 `config.yml` 可直接使用。新增的 `teleport.allow-cross-dimension` 与 `dimensions.*`
   都带默认值，其行为与 1.0.0 一致（允许跨维度、不限制维度）。
4. 如果你的 `dimensions.*` 里曾经写过 `OVERWORLD`，那些条目从本版起才真正生效——此前它们
   被忽略，命令在主世界是被拒绝的。

---

# EasyTP 1.1.0 (English)

2026-09-19 · PaperMC / Purpur 26.1.2 – 26.2 · Java 25

This release adds Folia support and declares Purpur compatibility, brings a per-command dimension
whitelist, a cross-dimension teleport switch, dimension-aware home icons and clickable TPA buttons,
and fixes three defects that failed silently.

## Added

**Folia support.** `plugin.yml` now declares `folia-supported: true`. The RTP engine's threading
abstraction was rebuilt on the four schedulers Paper and Folia share (`AsyncScheduler`,
`GlobalRegionScheduler`, `RegionScheduler`, `Entity#getScheduler()`); the old
`runSync` / `runLaterSync` / `runTimerSync` became `runGlobal` / `runLaterGlobal` /
`runTimerGlobal`, meaning "the global region tick thread", which is the ordinary main thread on
Paper. Delivery that touches a player is dispatched to that player's own region, and the two
synchronous chunk reads (`findSafeSpotSync`, `fastRevalidate`) refuse to run on a thread that does
not own the target region, falling back to the asynchronous path or reporting an ordinary search
failure instead of risking a crash.
**One JAR now runs on Paper, Purpur and Folia.**

**Per-command dimension whitelist.** The new `dimensions.<command>` section gates *which dimension
a command may be used from*. Leave it out and the command is unrestricted; a value that means
nothing is warned about once and ignored rather than locking the command out. The shipped defaults
open all three dimensions for `/rtp`, `/home`, `/sethome`, `/delhome`, `/tpa`, `/tphere`,
`/tpaccept` and `/tpdeny`.

**Cross-dimension teleport switch.** The new `teleport.allow-cross-dimension` option. When set to
`false`, `/home`, `/tpa`, `/tphere` and teleports from the `/homelist` GUI are refused when the
destination is in another dimension, and the player is told which two dimensions were involved.

**Dimension-aware home icons.** The `/homelist` icon block is now the dimension cue — grass block,
netherrack or end stone — and the lore shows the world, the coordinates and the localised dimension
name, or "unknown (world not loaded)" when that world is not loaded.

**Clickable TPA buttons.** Teleport requests now carry `[Accept]` and `[Deny]` buttons that run
`/tpaccept` and `/tpdeny`, with hover hints.

**`/homelist` is explicitly a view.** It opens in every dimension; each action taken inside it is
gated by the equivalent command instead — teleporting follows `dimensions.home` plus the
cross-dimension switch, relocating follows `dimensions.sethome`, deleting follows
`dimensions.delhome`. Renaming moves nothing, so it is not gated.

## Fixed

**`OVERWORLD` in a whitelist was always rejected.** Bukkit's enum names the overworld `NORMAL`,
while the shipped config and the language files both spell it `overworld`. The old code called
`World.Environment.valueOf` directly, so every `OVERWORLD` entry was dropped as unknown
and **the command was denied in the overworld** — with the default config that meant `/sethome`,
`/home`, `/rtp` and the rest. Entries are now resolved through the player-facing names first
(`OVERWORLD` / `WORLD` / `NORMAL`, `END` / `THE_END`, `THE_NETHER`) and fall back to the enum, so
configs that already spell it `NORMAL` keep working. Two neighbouring defects went with it: "no
restriction" used to be a fixed set of the three vanilla environments, which denied every gated
command to a player in a `CUSTOM` world; and each check resolved the whitelist twice, printing the
same warning twice.

**The ring capacity estimate truncated pi to 3.** `RingZone.area()` was written
`(long) Math.PI * (...)`, casting before multiplying, so the capacity estimate came out 246093
instead of 257708 — 4.5% low — and the spiral wrapped before it had covered the outermost ~96
blocks of the ring. The cast now happens after the multiplication.

**Dead branch in the column scan.** `determineUnsafeReason` had an `if` whose two branches returned
the same value. The first non-air block in the column is what disqualifies it, whether that block
is lava, water, bedrock or ordinary terrain.

## Build and compatibility

- **`paper-api` is pinned to `26.1.2.build.72-stable`** instead of the range
  `[26.1.2.build,)`. A Maven range resolves to the *highest* match — here a 26.3 alpha — so the
  plugin could compile against unreleased API while declaring `api-version: '26.1.2'`, and then
  throw `NoSuchMethodError` on the very servers it claims to support.
- The supported range is stated as PaperMC / Purpur **26.1.2 – 26.2** on Java 25, compiled against
  both ends and exercised on Paper 26.2.

## Repository content

This release also brings in the RTP algorithm write-up and its measurements:

- `docs/easytp-rtp-algorithm.md` — the design behind `/rtp` (deterministic spiral sampling, spatial
  memory, tiered preloading and backpressure), with the derivations and the measurements taken on
  Paper 26.2.
- `docs/bench/` — the raw measurement output, and `benchmarks/` — the harness that drives the
  production classes directly and can be re-run on a real server.

## Upgrading

1. **Delete the old JAR first.** Two EasyTP JARs in `plugins/` stop the plugin from loading; remove
   `EasyTP-1.0.0-SNAPSHOT.jar` before dropping in `easytp-1.1.0.jar`.
2. **No database migration.** Homes and RTP state keep their existing format.
3. An existing `config.yml` keeps working. The new `teleport.allow-cross-dimension` and
   `dimensions.*` keys both default to the 1.0.0 behaviour: cross-dimension teleports allowed, no
   dimension restriction.
4. If your `dimensions.*` lists ever contained `OVERWORLD`, those entries only start taking effect
   in this version — until now they were ignored and the command was denied in the overworld.
