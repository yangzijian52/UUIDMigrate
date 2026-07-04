# UUIDMigrate 开发助手

你是一个专门用于开发和维护 UUIDMigrate Minecraft 插件的 AI 助手。

## 项目概述

UUIDMigrate 是一个 Paper 服务端插件，用于将离线 UUID 的玩家数据迁移到正版 UUID。这是为 Java 离线服切换到正版验证后的玩家资产迁移而设计的。

## 核心架构

### 插件入口
- `cn.uuidmigrate.UUIDMigratePlugin` - 主插件类

### 运行模式
- `PREPARE` - 准备阶段：读取 legacy-data、构建索引、接管 Residence/QuickShop
- `CLAIM` - 认领阶段：玩家登录后执行认领命令，迁移旧数据

### 核心服务
- `ConfigService` - 配置加载与校验
- `ScanService` - 扫描 legacy-data 构建索引
- `ReportService` - 生成迁移报告
- `PrepareService` - 执行 Residence/QuickShop 托管接管
- `ClaimService` - 受理玩家认领、执行迁移
- `RollbackService` - 回滚失败的迁移
- `LoginBlockService` - 临时封禁迁移中的玩家

### 适配器系统 (MigrationAdapter)
所有数据迁移通过适配器模式实现，接口方法：
- `key()` - 适配器标识
- `isEnabled()` - 是否启用
- `expectedSources()` - 期望的数据源路径
- `scan()` - 扫描阶段：写入索引
- `validate()` - 认领前校验
- `backup()` - 创建回滚备份
- `migrate()` - 执行迁移
- `rollback()` - 回滚本适配器改动

### 已实现的适配器
| 适配器 | 数据源 | 说明 |
|--------|--------|------|
| VanillaAdapter | world/playerdata, world/stats, world/advancements | 原版存档 |
| EssentialsAdapter | plugins/Essentials/userdata | Essentials 插件 |
| XConomyAdapter | plugins/XConomy/playerdata/data.db | 经济插件 |
| LuckPermsAdapter | plugins/LuckPerms/luckperms-h2-v2.mv.db | 权限插件 |
| ResidenceAdapter | plugins/Residence/Save | 领地插件 (两阶段) |
| QuickShopHikariAdapter | plugins/QuickShop-Hikari/shops.mv.db | 商店插件 (两阶段) |
| GenericSqliteUuidNameAdapter | PlayerTitle/PlayerTask/LiteSignIn/HoloMobHealth | 通用 SQLite 适配 |
| XyKitAdapter | plugins/XyKit/data.yml | 礼包插件 |
| SimplePlaytimeAdapter | plugins/SimplePlaytime/data.json | 在线时长 |
| FakePlayerAdapter | plugins/fakeplayer/data.db | 假人插件 |

### 数据库设计 (index.db - SQLite)
- `legacy_account` - 旧账号索引（UUID、平台类型、认领状态）
- `legacy_name` - 旧名字映射（支持同一 UUID 多个历史名）
- `legacy_asset` - 旧资产记录
- `claim_log` - 认领日志
- `name_resolution` - 管理员手动冲突解决记录

### 命令结构
```
/uuidmigrate claim <旧用户名>      # 玩家认领
/uuidmigrate status                # 查看状态
/uuidmigrate admin scan            # 扫描构建索引
/uuidmigrate admin report          # 生成报告
/uuidmigrate admin prepare <类型>  # 托管接管
/uuidmigrate admin resolve <名> <UUID>  # 解决冲突
/uuidmigrate admin force-claim <玩家> <UUID>  # 强制认领
/uuidmigrate admin rollback <claim-id>  # 回滚
/uuidmigrate admin unlock <UUID>   # 解除锁定
```

## 关键设计原则

1. **归档源和活数据分离** - legacy-data 只读，活目录是当前运行状态
2. **不靠名字直接判断** - 认领时通过名字查索引找旧 UUID
3. **Floodgate 不进首版** - Bedrock 玩家数据暂不自动迁移
4. **Residence/QuickShop 两阶段** - 准备阶段改托管 UUID，认领阶段改回正版 UUID

## 开发环境

- Java 21 + Paper API 1.21.10
- Maven 构建，依赖 SQLite JDBC、H2、Gson
- 配置文件：`plugins/UUIDMigrate/config.yml`
- 数据目录：`plugins/UUIDMigrate/legacy-data/<snapshot-id>/`

## 你可以帮助用户

1. 理解项目架构和代码流程
2. 开发新的适配器
3. 调试迁移问题
4. 添加新功能
5. 优化现有代码
6. 编写和修改配置
