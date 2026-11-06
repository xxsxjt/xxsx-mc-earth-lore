# 地球：学识 / Earth: Lore (`earth_lore`)

地球系列的公共学识框架模组（对应全系列规划 10 册 `10-lore-framework.md`）。

本模组提供研究台、文献（空白笔记、研究笔记、典籍）、玩家与队伍知识存档、配方/机器/能力门禁 API、学识树界面、JEI 门禁装饰与旧进度迁移接口。本模组自身只内置 14 门学科的元数据定义（`GEO` / `CHE` / `MAT` / `ENG` / `AGR` / `BIO` / `MED` / `PHY` / `CMP` / `AST` / `FUT` / `XUA` / `MAG` / `THE`），不包含具体学科的知识节点；知识节点由各学科模组以数据包注册表形式提供。

## 版本与环境

- **Minecraft**：`26.1.2`
- **NeoForge**：`26.1.2.124`
- **Java**：`25`
- **Mod ID**：`earth_lore`
- **当前版本**：`0.1.0`
- **可选联动**：
  - `ftbteams`（`[26.1.2.1,)`，双端可选）：当 `knowledge_scope = team` 且 `team_source = auto/ftb_teams` 时共享 FTB Teams 队伍知识。
  - `jei`（`[29.53.0.112,)`，客户端可选）：为六类原版配方显示学识门禁锁标与品质上限提示，并为受门禁物品生成说明页。

## 目录结构

- `common/`：纯 Java 核心算法与校验器（DAG 拓扑校验、可达性检查、软硬门禁与品质上限计算、研究材料原子分配、载体外形匹配、传授表合并、迁移账本）及 JUnit 5 单元测试。
- `neoforge-26.1.2/`：NeoForge 26.1.2 模组工程（数据包注册表、方块与物品、服务端/客户端网络、Mixin 门禁、GUI 屏幕、JEI 插件与 23 项 GameTest）。

## 构建命令

在 `neoforge-26.1.2/` 目录下执行：

```powershell
.\gradlew.bat build --console=plain
```

产物路径：`neoforge-26.1.2/build/libs/earth-lore-neoforge-26.1.2-0.1.0.jar`。
验收记录见 [ACCEPTANCE.md](ACCEPTANCE.md)。
