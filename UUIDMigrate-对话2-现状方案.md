# UUIDMigrate 对话2：现状方案

## 职责

- 阅读项目文档和关键代码
- 梳理当前真实卡点
- 输出问题拆解和修复方案
- 标出高风险点、备选方案和验收条件

## 当前重点

- 重点分析 `QuickShop prepare` 为什么还没有闭环
- 区分：
  - 线程模型问题
  - 插件生命周期问题
  - 数据库连接/锁问题
  - 镜像服环境问题
- 给出最小可执行修复路径

## 不负责

- 不直接承担最终代码实现
- 不替代测试对话做日志验证

## 首句提示词

```text
你只负责 UUIDMigrate 的现状分析与修复方案，不直接承担最终实现。开始前请先阅读 K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-多对话协作说明.md、K:\ideaxiangmu\UUIDMigrate\agent.md、K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-交接进度.md、K:\ideaxiangmu\UUIDMigrate\UUIDMigrate插件设计文档.md，并重点阅读 UuidMigrateCommand、QuickShopHikariAdapter、PrepareService、BukkitSyncUtil 相关代码。然后输出当前卡点拆解、根因判断、最小修复路径、备选方案、风险点和验收标准。
```
