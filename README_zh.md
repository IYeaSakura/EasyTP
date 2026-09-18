# EasyTP

<div align="center">

[![PaperMC](https://img.shields.io/badge/PaperMC-26.1.2-004ee9?logo=minecraft&logoColor=white)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-e76f00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.9+-C71A36?logo=apache-maven)](https://maven.apache.org/)
[![Adventure](https://img.shields.io/badge/Adventure-MiniMessage-00bfa5?logo=bookstack)](https://docs.advntr.dev/minimessage/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

适用于 PaperMC 26.1.2 的轻量级传送插件，提供随机传送、TPA 请求和多回家管理。所有传送都使用可配置的延迟倒计时，支持移动和受伤取消、粒子效果，以及 MiniMessage 格式化的聊天输出。

[功能特性](#功能特性) | [技术栈](#技术栈) | [项目结构](#项目结构) | [快速开始](#快速开始) | [开发](#开发) | [构建与部署](#构建与部署) | [配置](#配置) | [命令与权限](#命令与权限) | [核心设计](#核心设计) | [故障排除](#故障排除) | [贡献](#贡献) | [许可证](#许可证)

---

## 功能特性

### 随机传送
- **标准 RTP**：传送到可配置半径内的随机安全地面位置。
- **螺旋采样**：坐标来自带权重环带上的黄金角螺旋，搜索不会退化成在海洋或岩浆湖上的重试循环。
- **异步校验**：候选区块在主线程之外通过 `ChunkSnapshot` 检查，随后同步传送。
- **空间记忆**：已判定为安全或不安全的格子会跳过、不加载区块，并以有界 LRU 缓存并持久化到 SQLite。
- **预载池**：安全位置提前进入热/冷/候选三级池，因此 `/rtp` 通常能立即返回。
- **结构 RTP（已废弃）**：`/rtp structure <structure>` 由 `rtp.structure.enabled` 控制，默认关闭，并计划移除。
- **世界边界感知**：越界坐标在任何校验之前就被丢弃。
- **不安全方块过滤**：可配置被视为不安全落脚点的方块列表。
- **液体规避**：可选禁止传送到水、岩浆、海带和气泡柱上。

### TPA 请求
- **/tpa <player>**：请求传送到另一名玩家身边。
- **/tphere <player>**：请求另一名玩家传送到你身边。
- **/tpaccept**：一键点击或命令接受待处理请求。
- **/tpdeny**：拒绝待处理请求。
- **可点击消息**：聊天消息中嵌入同意/拒绝按钮，支持悬停和点击事件。
- **请求超时**：待处理请求在配置超时后自动过期。

### 家
- **/sethome [name]**：将当前位置保存为命名家。
- **/home [name]**：传送至已保存的家。
- **/delhome [name]**：删除已保存的家。
- **/homelist**：打开分页箱子 GUI 列出所有家。
- **家 GUI**：左键传送，Shift + 右键删除，右键编辑；编辑菜单可将家重置为当前位置，或通过聊天重命名。图标按家所在维度选择（草方块、地狱岩、末地石）。
- **多个家**：可配置每位玩家最多可设置的家数量。
- **持久化存储**：家存储在内置 SQLite 数据库（`plugins/EasyTP/data.db`）中。首次启动会导入已有的 `homes.yml` 并重命名为 `homes.yml.migrated`。

### 延迟传送
- **倒计时计时器**：传送完成前可配置延迟。
- **标题倒计时**：可选屏幕标题显示剩余秒数。
- **移动取消**：移动会取消待处理传送。
- **受伤取消**：受到伤害会取消待处理传送。
- **粒子效果**：倒计时期间产生传送门与附魔粒子，到达时产生末地烛与村民粒子。

### 冷却与权限
- **按命令冷却**：每个命令都有自己的 `commands.<command>.cooldown`，**按玩家独立计时**，绝不跨玩家共享。设为 `0` 即关闭冷却。
- **按命令延迟**：倒计时长度按命令配置在 `commands.<command>.delay`。
- **命令类**：命令分为三类，每类有独立开关：

  | 类 | 开关 | 包含命令 |
  |----|------|----------|
  | RTP | `commands.rtp.enable` | `/rtp` |
  | 玩家传送 | `commands.player-teleport.enable` | `/tpa`、`/tphere`、`/tpaccept`、`/tpdeny` |
  | 家 | `commands.home.enable` | `/home`、`/sethome`、`/homelist`、`/delhome` |

  关闭某一类会**注销**该类命令，玩家无法看到或执行。修改开关需要重启服务器。
- **管理员绕过**：`easytp.admin.bypass-cooldown` 允许管理员跳过冷却。

### 维度控制

有两个互相独立的设置，一个管**命令能在哪使用**，另一个管**能把玩家送到哪**：

- **按命令白名单**：`dimensions.<command>` 把命令限制在指定维度内。每个命令都有自己的列表
  （`rtp`、`home`、`sethome`、`delhome`、`homelist`、`tpa`、`tphere`、`tpaccept`、`tpdeny`）。
  列表缺失或为空表示**不做限制**，这也是默认行为。
- **跨维度开关**：将 `teleport.allow-cross-dimension` 设为 `false` 后，任何会把玩家送到其他维度的
  传送都会被拒绝，并在提示中说明来源与目标维度。覆盖 `/home`、`/tpa`、`/tphere`、同意请求，
  以及从 `/homelist` GUI 传送。`/rtp` 不受影响——它只在玩家当前世界内搜索。
- **按维度区分家的图标**：`/homelist` 中主世界的家显示为草方块、下界为地狱岩、末地为末地石，
  物品 lore 中也会标注维度。

### 本地化
- **语言文件**：玩家可见文本位于 `lang/messages_en.yml` 和 `lang/messages_zh.yml`，由 `language` 设置选择。
- **回退**：所选语言中缺失的键会回退到内置的英文文件。

### 管理
- **热重载**：`/easytp reload` 无需重启服务器即可重新应用 `config.yml` 与语言文件（权限 `easytp.admin.reload`，默认 `op`）。

---

## 技术栈

### 核心技术

| 类别 | 技术 | 版本 |
|------|------|------|
| 平台 | PaperMC | 26.1.2 |
| 语言 | Java | 25 |
| 构建工具 | Maven | 3.9+ |
| 核心 API | `io.papermc.paper:paper-api` | 26.1.2.build.72-stable |
| 文本格式 | Adventure / MiniMessage | 由 Paper 提供 |

---

## 项目结构

```
EasyTP/
├── pom.xml                                # Maven 构建配置
├── LICENSE                                # MIT 许可证
├── README.md                              # 英文文档
├── README_zh.md                           # 中文文档
├── AGENTS.md                              # 面向 Agent 的开发指南
└── src/
    ├── main/
    │   ├── java/net/sakurain/mc/easytp/
    │   │   ├── EasyTPPlugin.java          # 插件入口
    │   │   ├── command/                   # 每个命令一个执行器，外加 Tab 补全
    │   │   ├── gui/                       # 家列表/编辑界面、图标、点击分发
    │   │   ├── listener/
    │   │   │   └── PlayerListener.java    # 移动、受伤和退出处理
    │   │   ├── manager/
    │   │   │   └── TeleportManager.java   # 冷却、延迟传送、TPA、家、特效
    │   │   ├── rtp/                       # 随机传送引擎
    │   │   │   ├── RtpEngine.java         # 预载池调度、异步校验、安全检查
    │   │   │   ├── RtpStorage.java        # RTP 状态的写回式持久化
    │   │   │   ├── SearchParams.java      # 由配置解析出的搜索参数
    │   │   │   ├── memory/                # 空间记忆缓存与格子状态
    │   │   │   ├── pool/                  # 热/冷/候选三级预载池
    │   │   │   ├── scheduler/             # 线程抽象（Bukkit 实现）
    │   │   │   └── spiral/                # 螺旋坐标生成器与环带
    │   │   ├── storage/                   # SQLite 连接、家仓库、家记录
    │   │   └── util/
    │   │       └── MessageUtil.java       # MiniMessage 加载与发送工具
    │   └── resources/
    │       ├── plugin.yml                 # 插件元数据与权限
    │       ├── config.yml                 # 默认配置
    │       └── lang/                      # messages_en.yml、messages_zh.yml
    └── test/                              # 当前无测试（预留）
```

---

## 快速开始

### 前置条件

- **服务器**：PaperMC 26.1.2
- **Java**：OpenJDK 25 或兼容版本
- **构建工具**：Maven 3.9+（仅从源码构建时需要）

### 安装

```bash
# 1. 构建插件 JAR
cd EasyTP
mvn clean package

# 2. 将产物复制到服务器 plugins 目录
cp target/easytp-1.0.0-SNAPSHOT.jar /path/to/server/plugins/

# 3. 启动或重启 Paper 服务器
```

首次启动时，插件会生成：

- `plugins/EasyTP/config.yml`
- `plugins/EasyTP/data.db`（SQLite；家与 RTP 空间记忆）
- `plugins/EasyTP/lang/messages_<locale>.yml`

---

## 开发

### 构建命令

| 命令 | 说明 |
|------|------|
| `mvn clean package` | 构建插件 JAR |
| `mvn -o clean package` | 离线构建（跳过远程元数据检查） |

> **没有测试阶段。** 项目目前没有任何自动化测试——`src/test` 不存在，POM 也未声明测试依赖。
> `mvn clean package` 只执行编译与打包，因此 `-DskipTests` 没有实际作用。所有验证都需要在 Paper 服务器上手动进行。

### 代码风格

#### 命名规范

| 项目 | 规范 | 示例 |
|------|------|------|
| 类 | PascalCase | `EasyTPPlugin.java`、`TeleportManager.java` |
| 方法 | camelCase | `randomTeleport`、`sendRequest` |
| 变量 | camelCase | `pendingRequests`、`cooldowns` |
| 常量 | SCREAMING_SNAKE_CASE | `MINI_MESSAGE` |
| 包 | lowercase | `net.sakurain.mc.easytp.command` |

### 约定

- 源码标识符使用英文。
- 玩家可见文本使用 MiniMessage。
- 修改玩家的传送操作在主线程执行。
- `/rtp` 的位置搜索异步执行，随后同步传送。

---

## 构建与部署

### 生产构建

```bash
cd EasyTP
mvn clean package
```

构建产物位于 `target/easytp-1.0.0-SNAPSHOT.jar`。

### 构建阶段

| 阶段 | 说明 |
|------|------|
| 1. 编译 | 编译 Java 25 源码 |
| 2. 打包 | 生成插件 JAR |

没有测试阶段：项目目前不包含自动化测试。

### 部署

1. 将 `target/easytp-1.0.0-SNAPSHOT.jar` 复制到 Paper 服务器的 `plugins/` 目录。
2. 启动或重启服务器。
3. 编辑 `plugins/EasyTP/config.yml` 以自定义消息、RTP 范围和冷却时间。

---

## 配置

所有配置位于 `plugins/EasyTP/config.yml`。

### 默认分区

| 分区 | 说明 |
|------|------|
| `language` | 加载的语言：`en` 或 `zh` |
| `database` | SQLite 数据库文件名 |
| `commands` | 类级开关与按命令的冷却 / 延迟 |
| `rtp` | 螺旋采样、空间记忆、预载池与安全选项 |
| `home` | 每位玩家最大家数量 |
| `tpa` | TPA 开关与请求超时 |
| `effects` | 粒子与音效 |

### 配置参考

权威默认值位于 [`src/main/resources/config.yml`](src/main/resources/config.yml)，
生成的 `plugins/EasyTP/config.yml` 与其保持一致。主要配置项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `language` | `zh` | 加载的语言文件 |
| `database.file` | `data.db` | SQLite 文件，相对于 `plugins/EasyTP/` |
| `commands.<class>.enable` | `true` | 类级开关：`rtp`、`player-teleport`、`home`。关闭即注销该类命令 |
| `commands.<command>.cooldown` | `rtp` 15、`tpa`/`tphere` 15、其余 0 | 冷却秒数，按玩家独立计时 |
| `commands.<command>.delay` | `rtp`/`tpa`/`tphere` 5、`home` 3 | 倒计时秒数 |
| `rtp.enabled` | `true` | `/rtp` 总开关 |
| `rtp.min-radius` / `max-radius` | `2000` / `5000` | 回退距离范围，仅在未配置 `ring-zones` 时使用 |
| `rtp.overworld-surface-only` | `true` | 主世界是否要求落点上方露天 |
| `rtp.max-scan-depth` | `16` | 从地表向下扫描的最大格数 |
| `rtp.spiral.grid-spacing` | `16` | 螺旋采样点之间的平均间距 |
| `rtp.spiral.ring-zones` | near/mid/far | 带权重的距离环带 |
| `rtp.spatial-memory.cell-size` | `32` | 空间记忆格子边长（格） |
| `rtp.spatial-memory.max-entries` | `50000` | 内存格子缓存上限（按最近最少使用淘汰） |
| `rtp.pool.base-size` | `12` | 预载池基础容量 |
| `rtp.pool.size-multiplier` | `2.0` | 每名在线玩家额外预载的位置数 |
| `rtp.pool.max-validations-per-tick` | `2` | 每 tick 校验的候选数 |
| `rtp.pool.max-chunk-loads-per-tick` | `2` | 每 tick 异步加载的区块数 |
| `rtp.pool.max-in-flight-loads` | `8` | 同时在途区块加载的硬上限（真正的背压） |
| `rtp.pool.player-wait-timeout-seconds` | `15` | 排队玩家等待预载池的最长秒数 |
| `rtp.pool.max-queued-players` | `10` | 预载池补充期间的排队上限 |
| `rtp.biome-blacklist` | 海洋、河流 | 主世界中跳过的生态域 |
| `rtp.allow-liquid` | `false` | 是否允许落在液体上 |
| `rtp.unsafe-blocks` | 岩浆、仙人掌、火…… | 永远不会被视为安全落脚点的方块 |
| `rtp.structure.max-safe-distance` | `256` | 在目标结构周围的搜索半径 |
| `rtp.structure.enabled` | `false` | 已废弃的结构传送，默认关闭 |
| `home.max-homes` | `5` | 每位玩家最大家数量 |
| `tpa.enabled` | `true` | TPA 请求总开关 |
| `tpa.timeout` | `30` | 待处理请求的过期秒数 |
| `teleport.show-title` | `true` | 屏幕标题倒计时 |
| `teleport.allow-cross-dimension` | `true` | 是否允许把玩家送到其他维度 |
| `dimensions.<command>` | 全部维度 | 该命令允许被使用的维度 |
| `effects.enabled` | `true` | 传送粒子与音效 |
| `debug.enabled` | `false` | 输出 `[DEBUG]` 详细控制台诊断 |
| `debug.summary-interval-seconds` | `30` | RTP 流水线汇总的打印间隔 |

消息**不在** `config.yml` 中，而是位于 [本地化](#本地化) 一节所述的语言文件里。

---

## 命令与权限

### 玩家命令

| 命令 | 权限 | 默认 | 说明 |
|------|------|------|------|
| `/rtp` | `easytp.command.rtp` | `true` | 随机传送至安全位置 |
| `/tpa <player>` | `easytp.command.tpa` | `true` | 请求传送到玩家身边 |
| `/tphere <player>` | `easytp.command.tphere` | `true` | 请求玩家传送到你身边 |
| `/tpaccept` | `easytp.command.tpaccept` | `true` | 接受待处理 TPA 请求 |
| `/tpdeny` | `easytp.command.tpdeny` | `true` | 拒绝待处理 TPA 请求 |
| `/sethome [name]` | `easytp.command.sethome` | `true` | 设置命名家（默认 `home`） |
| `/home [name]` | `easytp.command.home` | `true` | 传送至命名家 |
| `/delhome [name]` | `easytp.command.delhome` | `true` | 删除命名家 |
| `/homelist` | `easytp.command.homelist` | `true` | 打开家管理 GUI |

> `/rtp structure <structure>` **已废弃且默认关闭**。可通过 `rtp.structure.enabled: true` 重新启用，但计划移除。

`/easytp reload` 见 [管理员命令](#管理员命令)。

### 管理员权限

| 权限 | 默认 | 说明 |
|------|------|------|
| `easytp.admin.bypass-cooldown` | `op` | 绕过所有传送冷却 |
| `easytp.admin.reload` | `op` | 使用 `/easytp reload` |

### 管理员命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/easytp reload` | `easytp.admin.reload` | 重新读取 `config.yml` 与语言文件 |

### 热重载

`/easytp reload` 无需重启服务器即可重新读取 `config.yml` 与语言文件。

- **立即生效**：消息与 `language`；全部 `commands.<command>.cooldown` / `.delay`；`home.max-homes`；
  `tpa.*`；`rtp.enabled`；`teleport.show-title`；`effects.enabled`；以及 `rtp.spiral`、
  `rtp.spatial-memory`、`rtp.pool`、安全选项与结构传送各节。
- **需重启生效**：`commands.<class>.enable`（命令只在启动时注册一次）与 `database.file`
  （SQLite 连接已打开）。重载会检测到并明确告知。
- **失败安全**：应用之前会先解析文件。若 `config.yml` 不是合法 YAML，重载会被中止、当前配置继续
  正常工作——否则所有设置会静默回退到默认值。

---

## 核心设计

### 随机传送流程

预加载刻意保持"懒"：**没有玩家在线时完全不做任何事**，且区块加载有硬性并发上限，而不只是速率限制。

1. **生成** — 后台任务在带权重的环带上按黄金角螺旋采样坐标。螺旋索引按「世界 + 环带」持久化，因此一个完整周期内不会重复同一个点。生成是**自限的**：会统计已入池与已排队的数量，达到目标（`pool.base-size + 在线人数 × pool.size-multiplier`）即停止。
2. **跳过已知格子** — 每个坐标映射到空间记忆的格子，已记录为不安全的格子直接丢弃，不加载区块。
3. **拒绝越界** — 世界边界之外的坐标在任何校验之前就被丢弃。
4. **校验** — 异步加载候选区块，通过 `ChunkSnapshot` 检查，主线程不接触区块。加载同时受 `pool.max-validations-per-tick`（每 tick 请求数）与 `pool.max-in-flight-loads`（同时在途上限）约束。后者才是关键：没有它，地形生成一旦变慢，待处理的区块 IO 请求就会无界堆积。
5. **判定** — 按维度对纵列分级：
   - **主世界**：从最高的非空气方块向下扫描，最多 `max-scan-depth` 格。地板必须为固体且不在 `unsafe-blocks` 中，脚部与头部必须可通行；开启 `overworld-surface-only` 时还要求落点上方露天。
   - **下界 / 末地**：从天花板之下向下扫描，跳过基岩与液体表面。
6. **入池或丢弃** — 安全点进入预载池（区块已加载进热池，否则进冷池）；失败结果写入空间记忆，避免再次从磁盘检查同一格子。
7. **交付** — `/rtp` 从池中取出就绪位置并启动延迟传送。池为空时玩家会短暂排队，同时按需生成候选。若池中位置的区块已被卸载则会重新加载，若复核发现已不再安全，则回退到新生成的落点，而不是直接失败。

### 家 GUI

- `/homelist` 打开 54 格界面，每页显示 45 个家。
- 图标按世界维度选择（草方块、下界岩、末地石）。
- 左键传送，Shift + 右键删除，右键打开编辑菜单。
- 编辑菜单可将家重置为玩家当前位置，或发起重命名——重命名会消费下一条聊天消息。

### TPA 流程

1. 请求者向目标玩家发送请求。
2. 目标玩家收到带同意/拒绝按钮的可点击聊天消息。
3. 若接受，则在配置延迟后将正确玩家传送。
4. 若拒绝或超时，则通知双方且不执行传送。

### 延迟传送流程

1. 启动倒计时任务，每秒显示标题并生成粒子。
2. 若玩家移动或受伤，则取消任务。
3. 倒计时归零时异步传送玩家。
4. 在主线程显示到达粒子和完成消息。

---

## 故障排除

### RTP 总是失败

**问题**：`/rtp` 反复提示找不到安全位置。

**解决**：
- 确认世界边界覆盖所配置的环带——边界外的采样点会在校验前被拒绝。
- 缩小 `rtp.spiral.ring-zones` 的半径，或在过于严格时放宽 `rtp.biome-blacklist` / `rtp.unsafe-blocks`。
- 若 `/rtp` 反复重新检查同一区域，调大 `rtp.spatial-memory.max-entries`（被淘汰的格子会重新从 SQLite 读取）。
- 对于下界或末地，确认世界边界足够大。
- 检查服务器日志：异步区块加载失败会被记录，并向玩家反馈 `rtp-failed`。

### TPA 请求未收到

**问题**：目标玩家没有看到请求消息。

**解决**：
- 确保 `tpa.enabled` 为 `true`。
- 确认目标在线且未屏蔽聊天消息。
- 检查请求是否已过期。

### 回家传送失败

**问题**：`/home` 提示家未设置或世界未加载。

**解决**：
- 使用 `/homelist` 确认家名。
- 确保家所在世界已加载。
- 若设置新家失败，检查 `home.max-homes`。

### 构建失败

**问题**：构建时出现 `UnsupportedClassVersionError`。

**解决**：安装 JDK 25 并正确设置 `JAVA_HOME`。

```bash
java -version
# 预期：openjdk version "25" 或更高
```

### 用调试日志定位问题

设置 `debug.enabled: true` 并执行 `/easytp reload`，EasyTP 会输出它正在做什么。每行都带
`[DEBUG][分类]` 前缀，便于从服务器日志中 grep；分类有 `rtp`、`storage`、`teleport`、`cooldown`、
`reload`。

`rtp` 分类还会每隔 `debug.summary-interval-seconds` 打印一次汇总，**且在没有玩家在线时同样打印**
——那恰恰是最难察觉问题的场景：

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

怎么读：

| 现象 | 含义 |
|------|------|
| 无人在线却 `idleSkips=0`，且 `requested` 每次汇总都在涨 | 预加载没有进入静止状态——正是当年撑爆堆的那个失控行为 |
| `slow` / `slowest` 持续偏高 | 地形生成跟不上；调低 `rtp.pool.max-in-flight-loads` |
| `backpressureSkips` 与 `requested` 相当 | 在途上限正在起作用 |
| 服务器空闲时 `chunksStillLoaded` 仍接近池容量 | 这些被探测过的区块正被钉在内存里 |
| `misses` 远高于 `hits` 且 `cachedCells` 很低 | `rtp.spatial-memory.max-entries` 太小，LRU 在颠簸 |
| `pendingCells` 持续增长 | 落盘失败，或数据库跟不上 |
| `waitTimeouts` 大于 0 | 玩家请求 `/rtp` 的速度超过了池子的供给速度 |

生产环境请保持 debug **关闭**：输出很啰嗦，每次区块加载都会单独打印一行。

---

## 贡献

欢迎贡献。请遵循以下流程：

1. Fork 仓库。
2. 创建功能分支：`git checkout -b feature/your-feature`。
3. 按代码风格指南进行修改。
4. 本地构建并测试：`mvn clean package`。
5. 提交：`git commit -m 'feat: add new feature'`。
6. 推送：`git push origin feature/your-feature`。
7. 创建 Pull Request。

### 代码质量要求

提交 PR 前请确认：

- [ ] 构建成功（`mvn clean package`）
- [ ] 代码符合项目命名规范
- [ ] 无新增编译警告
- [ ] 玩家传送操作保留在主线程
- [ ] 新增消息使用 MiniMessage 并支持占位符

---

## 许可证

本项目采用 MIT 许可证。详情请参见 [LICENSE](LICENSE) 文件。

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

## 联系方式

- **作者**：Yuyang.Wang
- **网站**：[https://sakurain.net](https://sakurain.net)
- **邮箱**：[Yae_SakuRain@outlook.com](mailto:Yae_SakuRain@outlook.com)
- **GitHub**：[https://github.com/IYeaSakura](https://github.com/IYeaSakura)

---

<p align="center">
  Made by Yuyang.Wang
</p>
