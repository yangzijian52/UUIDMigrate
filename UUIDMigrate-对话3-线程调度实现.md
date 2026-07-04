# UUIDMigrate 对话3：线程调度实现

## 职责

- 负责命令入口与任务调度相关实现
- 负责同步/异步边界梳理
- 负责 Bukkit 主线程约束处理
- 负责 `prepare quickshop` 的调用链编排

## 当前重点

- 检查 `UuidMigrateCommand.runBackgroundTask(...)`
- 检查 `PrepareService.runPrepare(...)` 调用链
- 判断哪些步骤必须在主线程、哪些步骤可以异步
- 如果需要，重构 `prepare quickshop` 的执行模型

## 不负责

- 不负责最终文档收口
- 不负责镜像服实测结论
- 不负责泛化适配器业务逻辑本身

## 首句提示词

```text
你只负责 UUIDMigrate 的命令调度、线程模型和 Bukkit 主线程约束相关实现。开始前请先阅读 K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-多对话协作说明.md、K:\ideaxiangmu\UUIDMigrate\agent.md、K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-交接进度.md，并重点阅读 UuidMigrateCommand、PrepareService、BukkitSyncUtil 和 QuickShop prepare 调用链。请先明确哪些步骤必须同步、哪些步骤可以异步，再只在这个职责范围内推进代码实现。
```
