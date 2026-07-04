# UUIDMigrate

Minecraft Paper 离线 UUID 转正版 UUID 数据迁移插件。

## 当前构建

- 插件文件: `dist/UUIDMigrate-0.1.0-.jar`
- 目标 API: Paper `26.2`
- 运行要求: Java 21

## 主要能力

- 从离线服快照扫描旧 UUID、历史名称和资产索引。
- 支持玩家通过 `/uuidmigrate claim <旧离线服名字>` 发起 AuthMe 密码校验和数据迁移。
- 支持 Paper 26.2 的 Vanilla 玩家数据路径:
  - `world/players/data`
  - `world/players/stats`
  - `world/players/advancements`
- 兼容旧版 Vanilla 路径:
  - `world/playerdata`
  - `world/stats`
  - `world/advancements`
- CLAIM 模式下，玩家进服时若名称匹配唯一未迁移历史账号，会收到可点击迁移提示。

## 使用提示

迁移前先关闭服务器，运行 `一键转移工具.bat` 生成快照，再启动服务器执行扫描、报告、prepare 和 claim 流程。
