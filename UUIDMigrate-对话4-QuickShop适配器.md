# UUIDMigrate 对话4：QuickShop 适配器

## 职责

- 负责 `QuickShopHikariAdapter`
- 负责在线数据库接入方式
- 负责 `prepare / migrate / rollback` 的 QuickShop 逻辑正确性
- 负责 QuickShop 与持久化层交互的实现细节

## 当前重点

- 判断是否继续使用 QuickShop 托管连接
- 判断是否需要避免 `disablePlugin / enablePlugin`
- 保证 prepare 成功时不会破坏：
  - claim
  - rollback
  - runtime refresh

## 不负责

- 不负责全局命令入口与总体线程策略
- 不负责镜像服最终实测结论
- 不负责交接文档

## 首句提示词

```text
你只负责 UUIDMigrate 的 QuickShop 适配器与数据库接入实现。开始前请先阅读 K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-多对话协作说明.md、K:\ideaxiangmu\UUIDMigrate\agent.md、K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-交接进度.md，并重点阅读 QuickShopHikariAdapter、JdbcTableUtil、JdbcSnapshotStore、ClaimContext、PrepareContext 相关代码。请先判断当前 QuickShop prepare 的数据库接入和生命周期处理是否合理，再只在适配器职责范围内推进修复。
```
