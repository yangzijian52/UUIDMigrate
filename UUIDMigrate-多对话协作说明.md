# UUIDMigrate 多对话协作说明

这不是通用软件项目分工，而是专门给 `UUIDMigrate` 当前阶段使用的 6 个对话分工。

## 项目现状

当前项目：

- `K:\ideaxiangmu\UUIDMigrate`

项目目标：

- 把 Java 离线 UUID 玩家数据迁移到 Java 正版 UUID

当前阶段：

- 不是从零开发
- 不是泛化重构
- 不是前端项目
- 已经完成大部分适配器与主流程
- 已经进入镜像服验证和问题收尾阶段

当前最关键卡点：

- `QuickShop prepare` 仍未完全闭环
- 核心问题集中在：
  - 异步线程与 Bukkit 主线程约束
  - QuickShop 生命周期与数据库连接方式
  - 运行时验证与回归确认

## 所有对话开始前必须先读

1. `K:\ideaxiangmu\UUIDMigrate\agent.md`
2. `K:\ideaxiangmu\UUIDMigrate\UUIDMigrate-交接进度.md`
3. `K:\ideaxiangmu\UUIDMigrate\UUIDMigrate插件设计文档.md`
4. 当前项目代码目录 `K:\ideaxiangmu\UUIDMigrate\src`

如果要看镜像服上下文，再补读这些路径涉及的日志和配置：

- `D:\1.21.10\1.21.10\logs\latest.log`
- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\config.yml`

## 统一规则

- 6 个对话都只围绕 `UUIDMigrate` 这个项目工作。
- 不要把其他 Paper 26.1 升级项目的分工逻辑套进来。
- 所有对话都要默认当前主线任务是：推进 `QuickShop prepare` 可用，并保证不破坏现有已成功链路。
- 任何对话都不能擅自改项目目标范围：
  - 不引入 Floodgate 首版迁移
  - 不突然接手 AuthMe / CoreProtect / Citizens / TAB
- 如果某个对话发现任务超出自己边界，要明确指出应该交给哪个对话处理。

## 推荐启动顺序

1. 对话1：总控编排
2. 对话2：现状与方案分析
3. 对话3：命令调度与线程模型实现
4. 对话4：QuickShop 适配器与数据库接入实现
5. 对话5：镜像服验证与回归
6. 对话6：文档、交接与上线说明

## 当前这 6 个对话的分工目标

- 对话1：负责全局裁决和节奏
- 对话2：负责读项目、定方案、识别风险
- 对话3：负责命令入口、主异步调度、Bukkit 生命周期约束
- 对话4：负责 `QuickShopHikariAdapter`、数据库连接、prepare/claim/rollback 相关实现
- 对话5：负责镜像服日志、命令实测、问题复现与回归确认
- 对话6：负责交接文档、测试结论、发布风险说明

## 你现在怎么用

删掉当前对话后，直接新建 6 个对话。

每个对话第一句，不要自己临时组织，直接复制对应文件里的“首句提示词”。
