# UUIDMigrate 交接进度

更新时间：2026-04-12

## 1. 项目目标

这是一个 Paper 插件 `UUIDMigrate`，用于把离线服 Java 玩家数据迁移到正版服 Java UUID。

当前约定范围：

- 只迁 Java 离线 UUID -> Java 正版 UUID
- Floodgate / Bedrock 暂时不迁
- AuthMe、CoreProtect、Images、Citizens、TAB 不迁
- 登录系统不迁，玩家切正版后重新注册
- 玩家认领时会被临时封禁 5 分钟并踢出，避免在线覆盖失败
- Residence / QuickShop 采用托管身份方案，先 prepare，再在 claim 时归还

## 2. 当前代码状态

当前工作目录：

- `K:\ideaxiangmu\UUIDMigrate`

当前已构建并部署到镜像服的插件包：

- `K:\ideaxiangmu\UUIDMigrate\target\uuidmigrate-0.1.0-SNAPSHOT.jar`
- `D:\1.21.10\1.21.10\plugins\uuidmigrate-0.1.0-SNAPSHOT.jar`

镜像服路径：

- `D:\1.21.10\1.21.10`

## 3. 已完成事项

### 3.1 核心功能范围已确认

已纳入迁移：

- Vanilla `playerdata/stats/advancements`
- Essentials
- Residence
- QuickShop-Hikari
- XConomy
- LuckPerms
- PlayerTitle
- PlayerTask
- LiteSignIn
- SimplePlaytime
- HoloMobHealth
- XyKit
- fakeplayer

已确认不处理：

- Floodgate / Bedrock
- AuthMe
- CoreProtect
- Images
- Citizens
- TAB

### 3.2 归档工具已实现

文件：

- `K:\ideaxiangmu\UUIDMigrate\一键转移工具.bat`

作用：

- 把离线 UUID v3 的 Vanilla / Essentials 单人文件从 live 目录移动到 `plugins/UUIDMigrate/legacy-data/<snapshot-id>`
- 把共享数据库或共享配置复制到同一快照目录
- 保留 Floodgate UUID、Java 正版 UUID 数据在原地
- 生成清单和日志，供插件 `scan` 前校验

已修复：

- 结尾误输入 `.` 时会自动裁掉
- manifest 分隔与编码问题
- 不会误动 Floodgate / Java 正版 UUID 文件

### 3.3 已修复的代码问题

之前已修：

- 扫描时正确区分 `JAVA_OFFLINE / JAVA_ONLINE / FLOODGATE`
- `JAVA_ONLINE` 账号不会进入离线转正版 claim
- `MANUAL_INTERVENTION` 状态可正确写入数据库
- 多个 adapter 在 live 目标缺失时不会 silent success
- XConomy 流水更新不再按旧名字全表乱改
- Residence / QuickShop / LuckPerms / FakePlayer / XyKit / SimplePlaytime / GenericSqlite 类适配器补了 validate
- 归档清单解析支持 BOM
- H2 驱动版本从 `2.3.232` 降为 `2.1.214`，与 LuckPerms / QuickShop 实际库版本一致

### 3.4 QuickShop 锁库问题已修改代码

问题：

- `uuidmigrate admin prepare quickshop` 在服务器运行时失败
- 错误：`shops.mv.db` 被 QuickShop-Hikari 自己占用

已做修改：

- `src/main/java/cn/uuidmigrate/adapter/impl/QuickShopHikariAdapter.java`

当前逻辑：

- `prepare quickshop`
- `claim`
- `rollback`

都会先临时停用 `QuickShop-Hikari`，操作数据库后再重新启用，避免 H2 文件锁。

此修改已编译并覆盖到镜像服插件 jar，并且已经做过一次实测。
实测结论见下方“当前卡点”。

## 4. 镜像服当前已验证结果

### 4.1 插件启动正常

日志中已确认：

- `UUIDMigrate enabled. Mode=PREPARE, snapshot=2026-04-12-before-online-switch`

文件：

- `D:\1.21.10\1.21.10\logs\latest.log`

### 4.2 当前配置正确

文件：

- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\config.yml`

关键值：

- `mode: PREPARE`
- `snapshot-id: 2026-04-12-before-online-switch`
- `legacy-root: plugins/UUIDMigrate/legacy-data`

### 4.3 归档已成功

快照目录：

- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\legacy-data\2026-04-12-before-online-switch`

归档清单：

- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\reports\archive-manifest-2026-04-12-before-online-switch.txt`

归档日志：

- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\logs\archive-2026-04-12-before-online-switch.log`

清单状态：

- `status=SUCCESS`

### 4.4 扫描与报告已成功

已执行命令：

```text
uuidmigrate admin scan
uuidmigrate admin report
```

输出结果：

- Accounts: `665`
- Claimable: `357`
- Name conflicts: `16`
- Assets: `3636`

报告文件：

- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\reports\scan-summary-20260412-164506.md`
- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\reports\name-conflicts-20260412-164506.csv`
- `D:\1.21.10\1.21.10\plugins\UUIDMigrate\reports\unclaimed-assets-20260412-164506.csv`

### 4.5 Residence prepare 已成功

已执行命令：

```text
uuidmigrate admin prepare residence
```

输出结果：

- Assets indexed: `81`
- Owners updated: `81`
- Targets touched: `3`
- World files scanned: `3`
- Holder UUID: `11111111-1111-1111-1111-111111111111`

说明：

- Residence 托管准备已经成功

### 4.6 第二轮 scan / report / residence prepare 仍正常

已再次执行：

```text
uuidmigrate admin scan
uuidmigrate admin report
uuidmigrate admin prepare residence
```

结果：

- `scan` 仍成功
- `report` 仍成功
- `prepare residence` 成功，但因为上一轮已经处理过，所以这次：
  - `Owners updated: 0`
  - `Targets touched: 0`

这说明 Residence prepare 是幂等的，重复执行不会继续乱改。

## 5. 当前卡点

唯一未完成的步骤：

```text
uuidmigrate admin prepare quickshop
```

当前最新实测结果：

```text
uuidmigrate admin prepare quickshop
```

仍失败，但失败原因已经更明确，不再只是“数据库被占用”。

### 已定位的新问题

#### 问题 1：QuickShop disable / enable 发生在异步线程

`UuidMigrateCommand.runBackgroundTask(...)` 会把 `prepare` 放到异步线程执行。

而现在 `QuickShopHikariAdapter.withQuickShopDatabaseOffline(...)` 里直接调用：

- `pluginManager.disablePlugin(quickShopPlugin)`
- `pluginManager.enablePlugin(quickShopPlugin)`

这会触发两个问题：

1. `PlaceholderAPI` 的 `ExpansionUnregisterEvent` 只能同步触发
2. `QuickShop` 自己的某些清理逻辑也要求主线程

实测报错：

- `ExpansionUnregisterEvent may only be triggered synchronously.`
- `#[Illegal Access] This method require runs on server main thread.`

#### 问题 2：在当前 Leaves/Paper 环境里，直接 disable 后再 enable QuickShop 会触发 classloader 问题

实测报错：

- `The plugin classloader for QuickShop-Hikari has thrown a zip file error.`
- `zip file closed`

这说明当前这台服上，简单的 `disablePlugin -> enablePlugin` 并不可靠，至少不能这样在异步 prepare 流程里做。

#### 问题 3：最终数据库锁仍未释放

因为 QuickShop 没有被安全地停干净，所以最后还是回到了原始错误：

- `Database may be already in use ... [90020-214]`

## 6. 下一位 AI 接手后应立即做的事

### 第一步：不要再按当前实现直接重试 `prepare quickshop`

因为当前实现已经证明有副作用：

- 会让 QuickShop 在异步线程里 disable/enable
- 可能触发 PlaceholderAPI 主线程异常
- 可能触发 QuickShop classloader/zip closed 异常

### 第二步：优先修复调用线程模型

需要修的核心点：

- `QuickShop` 的停用/启用必须切到主线程执行
- 不能在 `runTaskAsynchronously` 里直接调 `disablePlugin/enablePlugin`

可选修法：

1. 在 adapter 内部封装“主线程执行并阻塞等待结果”
2. 或者对 `prepare quickshop` 单独改成整段主线程编排，数据库操作放到受控时机执行

### 第三步：重新评估是否应该走 disable/enable 插件方案

因为当前已经看到 `zip file closed`，所以下一位 AI 需要判断：

- 是不是应该改成调用 QuickShop 自身 API 释放数据库连接
- 或者是否应该把 `prepare quickshop` 改为“停服离线执行”
- 或者是否可以复制 live DB 到临时文件、修改后再原子替换

注意：如果选择“离线执行”，要重新评估 prepare 的产品方案是否还能接受。

### 第四步：修完后再重新测试

修完后再在镜像服里执行：

```text
uuidmigrate admin prepare quickshop
```

如果成功，再执行：

```text
uuidmigrate admin report
```

确认 QuickShop prepare 状态被写入报告。

### 第五步：最后再做一次上线前检查

看：

- `D:\1.21.10\1.21.10\logs\latest.log`

确认 `UUIDMigrate` 使用的是最新启动日志，没有新的报错。

## 7. 如果 QuickShop 仍失败，优先排查方向

1. `UuidMigrateCommand.runBackgroundTask(...)` 的异步执行与 QuickShop 主线程要求冲突
2. `PluginManager.disablePlugin(...)` / `enablePlugin(...)` 是否必须包进主线程同步调度
3. 当前 Leaves / Paper 环境中，QuickShop disable 后再 enable 是否天然不安全
4. 是否需要寻找 QuickShop 自带 API 来释放数据库，而不是直接停用整个插件
5. 如果仍走插件停用方案，要评估 PlaceholderAPI expansion 注销流程的同步要求
6. 如果仍无法在线处理，评估是否改为停服 prepare quickshop

## 8. 当前关键源码文件

- `K:\ideaxiangmu\UUIDMigrate\src\main\java\cn\uuidmigrate\adapter\impl\QuickShopHikariAdapter.java`
- `K:\ideaxiangmu\UUIDMigrate\src\main\java\cn\uuidmigrate\adapter\impl\LuckPermsAdapter.java`
- `K:\ideaxiangmu\UUIDMigrate\src\main\java\cn\uuidmigrate\util\ArchiveManifestUtil.java`
- `K:\ideaxiangmu\UUIDMigrate\src\main\resources\config.yml`
- `K:\ideaxiangmu\UUIDMigrate\一键转移工具.bat`

## 9. 当前结论

当前项目已经不是“不能用”的状态了，实际已经完成到：

- 归档成功
- 扫描成功
- 报告成功
- Residence prepare 成功

只剩：

- `QuickShop prepare` 尚未解决

而且现在已经明确不是简单的 H2 版本或等待时间问题，而是：

- 异步线程里操作插件生命周期
- PlaceholderAPI 同步事件要求
- QuickShop 在当前环境下 disable/enable 后 classloader 出错

下一位 AI 需要优先处理这个线程模型与生命周期问题，再继续推进。
