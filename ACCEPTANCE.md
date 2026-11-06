# Earth: Lore (`earth_lore`) 验收清单

对照规划文档 `docs/earth-series-plan/10-lore-framework.md` 的 **A-023 验收** 与 A-001..A-022 规格条目。

## 1. 当前层级状态

- **源码/计划**：源码完成（A-001..A-023 全条目实现，含 FTB Teams 26.1.2.5 兼容）。
- **构建/单测**：构建通过（`gradlew.bat build` 成功，`common` 8 个 JUnit 测试类共 22 条测试全绿，`tools/mcver.py jars` 零分包零冲突）。
- **候选产物**：
  - 路径：`neoforge-26.1.2/build/libs/earth-lore-neoforge-26.1.2-0.1.0.jar`
  - 大小：`384012` 字节
  - SHA-256：`A588CFE8BACF049A60420753E0800044832C24AF5686187707C5AD7AA7012A48`
- **运行/游戏内验证**：**未执行**（遵循会话硬性禁令“严禁启动游戏客户端/不要启动游戏测试”，未跑 `runClient` 与 `runGameTestServer`）。

## 2. JUnit 单元测试覆盖（已执行并通过）

| 测试类 | 用例数 | 覆盖逻辑 | 状态 |
|---|---:|---|---|
| `AllocationTest` | 3 | 研究材料跨槽原子扣除与不足时零扣除 | 通过 |
| `CarrierFormsTest` | 2 | 载体外形 `nodes > discipline > default` 优先级匹配 | 通过 |
| `GateMathTest` | 4 | `root / theory / soft / hard / cap` 五种门禁与品质上限计算 | 通过 |
| `GraphValidatorTest` | 6 | 学科存在性、层级范围 `0..15`、前置层级顺序、环检测、跨模组必选前置检查、重复解锁检查 | 通过 |
| `ListMergeTest` | 1 | 多数据包传授列表稳定去重合并 | 通过 |
| `MigrationLedgerTest` | 2 | 旧进度迁移账本幂等判重 | 通过 |
| `ReachabilityTest` | 2 | 知识图可达性传递闭包与 `lore_unreachable` 检出 | 通过 |
| `StudyMathTest` | 2 | 研读耗时公式 `ceil((40 + 40 * tier) * factor)` 与典籍拓扑排序 | 通过 |

## 3. A-023 GameTest 对照表（已编写源码并编译通过，待运行验证）

全部测试位于 `com.xxsx.earthlore.gametest.LoreGameTests` 与 `FtbTeamTest`：

| GameTest 名称 | 所在类 | 环境定义 | 当前状态 | 补充覆盖 |
|---|---|---|---|---|
| `earth_lore_root_known` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_study_ok` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `StudyMathTest` |
| `earth_lore_study_prereq_missing` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `StudyMathTest` |
| `earth_lore_research_atomic` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `AllocationTest` |
| `earth_lore_research_pause` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_cancel_refund` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_copy` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_treatise_order` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `StudyMathTest` |
| `earth_lore_soft_cap` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `GateMathTest` |
| `earth_lore_cap_heirloom` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `GateMathTest` |
| `earth_lore_hard_lock` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `GateMathTest` |
| `earth_lore_owner_snapshot` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_any_alt` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `GraphValidatorTest` |
| `earth_lore_migration_idempotent` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `MigrationLedgerTest` |
| `earth_lore_death_keep` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_unloaded_keep` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_condition_provider` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_learned_event` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | — |
| `earth_lore_carrier_filter` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `CarrierFormsTest` |
| `earth_lore_teaching_merge` | `LoreGameTests` | `player_scope` | 源码完成（未跑） | `ListMergeTest` |
| `earth_lore_team_scoreboard` | `LoreGameTests` | `team_scoreboard` | 源码完成（未跑） | — |
| `earth_lore_team_ftb` | `FtbTeamTest` | `team_ftb`（需装 `ftbteams`） | 源码完成（未跑） | — |
| `earth_lore_scope_index` | `LoreGameTests` | `server_scope` | 源码完成（未跑） | — |

## 4. 待用户在游戏内验收的项目

1. **创造栏可见**：打开创造模式物品栏“地球：学识（Earth: Lore）”页签，确认可见 `research_table`（研究台）、`blank_notes`（空白笔记），以及加载学科节点后自动展开的各节点 `research_notes`（研究笔记）。
2. **命令烟雾测试**：
   - `/give @s earth_lore:research_table`、`/give @s earth_lore:blank_notes`
   - `/lore list`、`/lore tree geo`、`/lore validate`、`/lore team`
3. **方块放置与研究台 GUI**：放置研究台并右键打开界面，检查研究 / 抄写 / 汇编三模式切换、槽位提示、条件核对圆点及进度条绘制。
4. **文献着色与研读 HUD**：观察不同学科研究笔记与典籍的封面染色（无组件时为默认灰 `#808080`），长按右键研读时屏幕中央上方显示进度条与字幕。
5. **学识树界面**：按 `K` 键打开学识树，检查顶部 14 门学科页签切换、拖拽/滚轮平移、跨学科虚线框节点点击跳转。
6. **JEI 门禁显示**：安装 JEI 时，查看受门禁配方的右上角锁标与悬停提示，以及受门禁物品的说明页。
