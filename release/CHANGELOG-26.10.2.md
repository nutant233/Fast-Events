# Fast-Events 26.10.2

## English

### Fixes
- Fixed a crash (ArrayIndexOutOfBoundsException in the dispatcher cache) when a listener was registered while another
  thread posted an event — e.g. Embeddium chunk build threads posting platform events.
- Fixed a startup crash on 1.21 where generating a dispatcher for a listener from another mod could fail with
  NoClassDefFoundError. Dispatchers no longer name any mod's classes, so class-loader isolating setups (spark, AE2,
  etc.) are safe.

### Performance
- Call sites that post more than four different event classes now switch their cache to a map instead of re-resolving
  on every post: 16 event classes at one call site went from 143 to 33 ns per post (vanilla: 66 ns).
- 1.21: consumer listeners are called directly instead of through the bus's wrapper (−36%), with the cancellation
  filter inlined; the generated dispatcher also returns the cancel flag in the same call.
- No regressions on the common cases (verified by a 5-round A/B with control cases): 8 consumers 19.4 → 6.9 ns,
  8 @SubscribeEvent 35.8 → 10.8 ns per post on 1.21.1 / Java 21.

### Files
- Minecraft 1.16.5 (Forge 36+) — `fastevents-1.16-26.10.2.jar` (Java 8)
- Minecraft 1.18.2 (Forge 40+) — `fastevents-1.18-26.10.2.jar` (Java 17)
- Minecraft 1.19.2 (Forge 43+) — `fastevents-1.19-26.10.2.jar` (Java 17)
- Minecraft 1.20.1 (Forge 47+) — `fastevents-1.20-26.10.2.jar` (Java 17)
- Minecraft 1.21.1 (NeoForge 21.1.169+) — `fastevents-1.21-26.10.2.jar` (Java 21)

### Notes
- Drop the jar into `mods/` (or the launcher's classpath); no config, no dependencies.
- Optional flags: `-Dfastevents.guard=false` (disable the no-listener guard), `-Dfastevents.audit=true`,
  `-Dfastevents.diag=true`, `-Dfastevents.exclude=a.b,c.d` (packages to leave untouched).

## 中文

### 修复
- 修复「注册监听器的同时另一个线程在 post 事件」导致的崩溃（分发器缓存里的 ArrayIndexOutOfBoundsException），
  例如 Embeddium 在区块构建线程 post 平台事件时。
- 修复 1.21 上为其它模组的监听器生成分发器时可能抛 NoClassDefFoundError 的启动崩溃。生成类不再引用任何模组的类，
  因此对 spark、AE2 这类模组类加载器隔离的环境是安全的。

### 性能
- 一个调用点发布超过 4 种事件类时，缓存自动切换成 map，不再每次重新解析：16 种事件类共用调用点从 143 ns 降到
  33 ns 每次 post（原生 66 ns）。
- 1.21：consumer 监听器直接从生成类调用（−36%），取消过滤内联；生成的分发器在同一次调用里返回取消状态。
- 常见场景无回退（5 轮带对照组的 A/B 实测）：1.21.1 / Java 21 上 8 个 consumer 19.4 → 6.9 ns，8 个 @SubscribeEvent
  35.8 → 10.8 ns 每次 post。

### 文件
- 1.16.5（Forge 36+）— `fastevents-1.16-26.10.2.jar`（Java 8）
- 1.18.2（Forge 40+）— `fastevents-1.18-26.10.2.jar`（Java 17）
- 1.19.2（Forge 43+）— `fastevents-1.19-26.10.2.jar`（Java 17）
- 1.20.1（Forge 47+）— `fastevents-1.20-26.10.2.jar`（Java 17）
- 1.21.1（NeoForge 21.1.169+）— `fastevents-1.21-26.10.2.jar`（Java 21）

### 说明
- jar 放进 `mods/`（或启动器 classpath）即可，无需配置、无前置。
- 可选参数：`-Dfastevents.guard=false`（关闭「无人监听就不构造事件」的守卫）、`-Dfastevents.audit=true`、
  `-Dfastevents.diag=true`、`-Dfastevents.exclude=a.b,c.d`（不处理的包）。
