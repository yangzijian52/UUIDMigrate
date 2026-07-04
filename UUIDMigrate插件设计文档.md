# UUIDMigrate 插件设计文档

时间: 2026-04-11

适用服务端镜像: `D:\1.21.10\1.21.10`

关联方案: `镜像服UUID迁移盘点.md`

## 一、定位

`UUIDMigrate` 是一个给 Paper 服务端使用的迁移插件，用来处理这次从 Java 离线服切换到 Java 正版验证后的玩家资产迁移。

首版只做两件事:

1. 读取已经归档好的旧玩家数据
2. 把旧离线 UUID 对应的数据，迁到玩家当前登录的正版 UUID

首版不做:

1. 自动迁移 Floodgate / Bedrock 玩家数据
2. 自动迁移登录系统账号
3. 自动推断“同名就一定是同一个人”

## 二、首版范围

### 1. 纳入迁移

1. 原版 `world/playerdata`
2. 原版 `world/stats`
3. 原版 `world/advancements`
4. `Essentials`
5. `Residence`
6. `QuickShop-Hikari`
7. `XConomy`
8. `PlayerTitle`
9. `PlayerTask`
10. `LiteSignIn`
11. `LuckPerms`
12. `SimplePlaytime`
13. `HoloMobHealth`
14. `XyKit`
15. `fakeplayer`

### 2. 明确排除

1. `Floodgate` / Bedrock 玩家数据
2. `AuthMe`
3. `CoreProtect`
4. `Images`
5. `Citizens`
6. `TAB`

## 三、运行模式

同一个插件，分两个阶段使用。

### 1. 准备阶段

用途:

1. 读取 `legacy-data`
2. 构建旧账号索引
3. 接管 `Residence` 和 `QuickShop`
4. 生成迁移前检查报告

这个阶段建议在切正版前的维护期执行。

### 2. 认领阶段

用途:

1. 玩家用正版账号进入服务器
2. 玩家执行认领命令
3. 插件把归档目录里的旧数据写回当前正版 UUID

这个阶段在切正版后使用。

## 四、目录与文件设计

插件工作目录:

```text
plugins/UUIDMigrate/
  config.yml
  index.db
  logs/
  backups/
  reports/
  legacy-data/
    <snapshot-id>/
```

说明:

1. `legacy-data/<snapshot-id>/` 是旧数据只读源
2. `index.db` 是插件自己的索引库
3. `backups/` 保存每次认领前的回滚备份
4. `reports/` 保存扫描报告、冲突报告、未认领报告
5. `logs/` 保存迁移流水

### 1. 外部工具位置

除了 Paper 插件本体，还需要一个外部批处理工具:

`一键转移工具.bat`

放置位置:

1. 和 `server.jar` 同级
2. 也就是服务端根目录

原因:

1. 它本质上是切正版前的归档工具
2. 用相对路径就能直接定位当前服务器根目录
3. 不需要玩家进服，不依赖 Bukkit 命令环境

### 2. 外部工具产物

`一键转移工具.bat` 运行后至少要生成:

1. `plugins/UUIDMigrate/legacy-data/<snapshot-id>/`
2. `plugins/UUIDMigrate/logs/archive-<time>.log`
3. `plugins/UUIDMigrate/reports/archive-manifest-<time>.txt`

## 五、整体切服流程

### 1. 切正版前

管理员手工完成:

1. 停服
2. 整服备份
3. 在服务端根目录运行 `一键转移工具.bat`
4. 按最终方案把旧玩家数据整理到 `legacy-data/<snapshot-id>/`
5. 保留 `Residence` 和 `QuickShop` 的在线活数据，准备做托管接管

然后启动服务器，执行:

1. `admin scan` 构建索引
2. `admin report` 检查冲突和缺失
3. `admin prepare residence`
4. `admin prepare quickshop`
5. 确认托管完成
6. 停服，切换正版验证

### 2. 切正版后

1. 玩家用正版账号进服
2. 玩家执行认领命令
3. 插件封禁 5 分钟并踢出玩家
4. 离线执行迁移
5. 成功后提前解除封禁
6. 玩家重新进服

## 六、一键转移工具.bat 设计

这是插件配套的外部工具，不是 Paper 指令。

### 1. 目标

作用只有一个:

1. 自动把首版需要迁移的旧数据，归档到 `plugins/UUIDMigrate/legacy-data/<snapshot-id>/`

### 2. 运行方式

建议支持:

```bat
一键转移工具.bat
一键转移工具.bat 2026-04-11-before-online-switch
```

规则:

1. 不传参数时，脚本提示输入 `snapshot-id`
2. 传了参数时，直接使用参数作为 `snapshot-id`
3. 根目录通过 `%~dp0` 取得

### 3. 执行前检查

脚本启动后先做:

1. 检查是否位于服务端根目录
2. 检查 `plugins`、`world` 等目录是否存在
3. 检查目标快照目录是否已存在
4. 提示“确认服务器已关闭”

建议行为:

1. 如果目标快照目录已存在且非空，直接退出
2. 不做覆盖归档

### 4. 归档策略

不是所有数据都能硬移走，首版按两种策略处理。

#### A. 直接移动

这些属于“玩家个人数据”，归档后应从活目录移走，让玩家切正版后先以新号进入:

1. `world/playerdata`
2. `world/stats`
3. `world/advancements`
4. `plugins/Essentials/userdata`
5. `plugins/XConomy/playerdata/data.db`
6. `plugins/PlayerTitle/PlayerTitle.db`
7. `plugins/PlayerTask/PlayerTask.db`
8. `plugins/LiteSignIn/Database.db`
9. `plugins/LuckPerms/luckperms-h2-v2.mv.db`
10. `plugins/SimplePlaytime/data.json`
11. `plugins/HoloMobHealth/database.db`
12. `plugins/XyKit/data.yml`
13. `plugins/fakeplayer/data.db`

#### B. 归档复制，活目录保留

这两项属于共享世界状态，切正版后还要给插件做托管接管，所以不能直接移走:

1. `plugins/Residence/Save`
2. `plugins/QuickShop-Hikari/shops.mv.db`

处理方式:

1. 复制一份到 `legacy-data`
2. 原位置保留给 `admin prepare` 使用

### 5. 明确不归档的内容

脚本不处理:

1. `Floodgate`
2. `AuthMe`
3. `CoreProtect`
4. `Images`
5. `Citizens`
6. `TAB`

### 6. 目录结构要求

脚本生成的目标目录必须保留原始相对路径。

例如:

```text
plugins/UUIDMigrate/legacy-data/2026-04-11-before-online-switch/
  world/playerdata/
  world/stats/
  world/advancements/
  plugins/Essentials/userdata/
  plugins/Residence/Save/
  plugins/QuickShop-Hikari/shops.mv.db
  plugins/XConomy/playerdata/data.db
  plugins/PlayerTitle/PlayerTitle.db
  plugins/PlayerTask/PlayerTask.db
  plugins/LiteSignIn/Database.db
  plugins/LuckPerms/luckperms-h2-v2.mv.db
  plugins/SimplePlaytime/data.json
  plugins/HoloMobHealth/database.db
  plugins/XyKit/data.yml
  plugins/fakeplayer/data.db
```

### 7. 日志与清单

脚本至少输出:

1. 每个源路径是否存在
2. 每个路径最终是 `MOVE`、`COPY` 还是 `SKIP`
3. 目标快照目录
4. 执行开始时间、结束时间

建议同时生成:

1. `archive-<time>.log`
2. `archive-manifest-<time>.txt`

### 8. 脚本实现要求

建议脚本本身满足:

1. 中文提示
2. 对不存在的源路径只记录 `SKIP`，不直接报错退出
3. 对必须存在的核心路径可给出黄色警告
4. 任何 `MOVE`/`COPY` 失败立即终止并返回非零退出码
5. 不依赖 PowerShell 才能运行，优先纯 `.bat`

### 9. 后续与插件的关系

脚本只负责“整理旧数据到归档目录”。

真正负责:

1. 建索引
2. 托管接管
3. 玩家认领
4. 回滚

这些仍然由 `UUIDMigrate` 插件本体完成。

## 七、最关键的设计原则

### 1. 归档源和活数据分离

这次不能把“归档源”和“在线使用的数据”混在一起。

首版按下面的原则做:

1. `legacy-data` 中的旧数据，默认只读，不直接改
2. 玩家真正上线要读的，是当前服务端活目录中的数据
3. `Residence` / `QuickShop` 为了过渡期不中断，要保留活数据并改成托管拥有者
4. 玩家认领时，真正的迁移源是 `legacy-data` 里的原始旧数据

也就是说:

1. `legacy-data` 保存“原件”
2. 活目录保存“当前可运行状态”
3. 插件自己的 `index.db` 负责把两边串起来

### 2. 不靠名字直接拍板

玩家认领时，输入的是旧用户名，但插件内部真正认的是“旧 UUID”。

流程是:

1. 用旧用户名查索引
2. 找到唯一旧 UUID
3. 再迁移旧 UUID 对应的所有资产

如果同一个旧用户名在索引里对应多个旧 UUID:

1. 玩家认领直接失败
2. 必须管理员手动绑定

### 3. Floodgate 不进首版自动迁移

命中下列任一条件的旧账号，首版直接跳过:

1. UUID 形如 `00000000-0000-0000-0009-*`
2. 扫描时被 Floodgate API 识别为 Bedrock
3. 配置里显式标记跳过

## 八、插件模块设计

### 1. `ConfigService`

负责:

1. 加载配置
2. 校验路径
3. 校验托管 UUID
4. 校验当前运行模式

### 2. `IndexService`

负责:

1. 扫描 `legacy-data`
2. 建立旧用户名、旧 UUID、数据来源之间的索引
3. 记录认领状态
4. 提供冲突查询

### 3. `PrepareService`

负责:

1. 执行 `Residence` 托管接管
2. 执行 `QuickShop` 托管接管
3. 生成托管映射表
4. 校验外部归档工具产物是否完整

### 4. `ClaimService`

负责:

1. 受理玩家认领
2. 校验资格
3. 创建回滚点
4. 执行各适配器迁移
5. 更新索引状态

### 5. `BanService`

负责:

1. 给认领玩家临时封禁
2. 踢出玩家
3. 迁移完成后解除封禁

### 6. `RollbackService`

负责:

1. 备份迁移前的目标数据
2. 任何一步失败时回滚已写入内容
3. 写出失败报告

### 7. `AdapterRegistry`

负责:

1. 注册所有数据适配器
2. 按顺序执行迁移
3. 汇总每个适配器结果

## 九、命令设计

### 1. 玩家命令

`/uuidmigrate claim <旧用户名>`

用途:

1. 玩家认领旧离线号
2. 插件按索引找到旧 UUID
3. 开始迁移

`/uuidmigrate status`

用途:

1. 查看自己是否已认领
2. 查看最近一次迁移结果

### 2. 管理员命令

`/uuidmigrate admin scan`

用途:

1. 扫描 `legacy-data`
2. 重建或更新索引库

`/uuidmigrate admin report`

用途:

1. 输出扫描报告
2. 输出重名冲突
3. 输出缺失数据项

`/uuidmigrate admin prepare residence`

用途:

1. 把当前活目录中的 Residence 资产改挂托管 UUID
2. 生成 `旧 owner uuid -> residence` 映射

`/uuidmigrate admin prepare quickshop`

用途:

1. 把当前活目录中的 QuickShop 商店改挂托管 UUID
2. 生成 `旧 owner uuid -> shop id` 映射

`/uuidmigrate admin resolve <旧用户名> <旧UUID>`

用途:

1. 处理重名冲突
2. 指定某个旧用户名对应哪个旧 UUID

`/uuidmigrate admin force-claim <玩家> <旧UUID>`

用途:

1. 直接把指定旧 UUID 迁给指定在线玩家
2. 用于异常处理

`/uuidmigrate admin rollback <claim-id>`

用途:

1. 回滚某次失败或误操作的认领

`/uuidmigrate admin unlock <旧UUID>`

用途:

1. 解除旧账号锁定
2. 允许重新认领

## 十、权限设计

1. `uuidmigrate.player.claim`
2. `uuidmigrate.player.status`
3. `uuidmigrate.admin.scan`
4. `uuidmigrate.admin.report`
5. `uuidmigrate.admin.prepare`
6. `uuidmigrate.admin.resolve`
7. `uuidmigrate.admin.forceclaim`
8. `uuidmigrate.admin.rollback`
9. `uuidmigrate.admin.unlock`

## 十一、配置设计

建议 `config.yml`:

```yaml
mode: CLAIM

legacy-root: plugins/UUIDMigrate/legacy-data
snapshot-id: 2026-04-11-before-online-switch

temporary-ban-seconds: 300
kick-message: "正在迁移你的旧数据，请稍后重新进入服务器"
ban-reason: "UUIDMigrate: 数据迁移中"

skip-floodgate-players: true
floodgate-prefixes:
  - "PE"

allow-offline-uuid-fallback: false
allow-repeat-claim: false
dry-run: false
log-detail: true

residence-holder:
  uuid: "11111111-1111-1111-1111-111111111111"
  name: "UUIDMigrate_Residence"

quickshop-holder:
  uuid: "22222222-2222-2222-2222-222222222222"
  name: "UUIDMigrate_Shop"

adapters:
  vanilla: true
  essentials: true
  xconomy: true
  luckperms: true
  residence: true
  quickshop: true
  playertitle: true
  playertask: true
  litesignin: true
  xykit: true
  simpleplaytime: true
  holomobhealth: true
  fakeplayer: true
```

说明:

1. `mode` 只允许 `PREPARE` 或 `CLAIM`
2. `allow-offline-uuid-fallback` 默认关
3. 托管 UUID 必须固定，不允许每次自动生成
4. 适配器可按配置逐项关闭，便于排错

另外建议加一项:

```yaml
archive-tool:
  expected-marker-enabled: true
  expected-manifest-prefix: "archive-manifest-"
```

用途:

1. `admin scan` 前先检查外部归档工具是否已经跑过
2. 避免管理员忘了做归档就直接切到认领流程

## 十二、索引库设计

插件自己的索引库建议用 SQLite，文件名:

`plugins/UUIDMigrate/index.db`

原因:

1. 数据有结构化关系
2. 有冲突处理需求
3. 有回滚、锁定、日志需求
4. 比 YAML 更适合做认领状态机

### 1. `legacy_account`

字段建议:

1. `legacy_uuid`
2. `platform_type`
   - `JAVA_OFFLINE`
   - `JAVA_ONLINE`
   - `FLOODGATE`
3. `primary_name`
4. `claim_status`
   - `UNCLAIMED`
   - `LOCKED`
   - `CLAIMED`
   - `FAILED`
5. `claimed_by_uuid`
6. `claimed_by_name`
7. `claimed_at`
8. `notes`

### 2. `legacy_name`

字段建议:

1. `legacy_uuid`
2. `name`
3. `source`
4. `is_primary`

用途:

1. 同一个旧 UUID 可以有多个历史名字
2. 认领时先查这张表

### 3. `legacy_asset`

字段建议:

1. `legacy_uuid`
2. `adapter`
3. `asset_key`
4. `asset_meta_json`

用途:

1. 记录这个旧账号拥有哪些资产
2. 尤其给 `Residence` / `QuickShop` 用

### 4. `claim_log`

字段建议:

1. `claim_id`
2. `legacy_uuid`
3. `new_uuid`
4. `new_name`
5. `started_at`
6. `finished_at`
7. `status`
8. `error_message`

### 5. `name_resolution`

字段建议:

1. `legacy_name`
2. `chosen_legacy_uuid`
3. `resolved_by`
4. `resolved_at`

用途:

1. 存管理员手动冲突处理结果

## 十三、旧账号识别策略

### 1. 扫描来源

首版从这些地方提取“旧 UUID - 名字”关系:

1. `world/playerdata`
2. `world/stats`
3. `world/advancements`
4. `Essentials/userdata`
5. `Residence/Save/PlayerData`
6. `XConomy`
7. `PlayerTitle`
8. `PlayerTask`
9. `LiteSignIn`
10. `LuckPerms`
11. `QuickShop`
12. `HoloMobHealth`
13. `XyKit`
14. `fakeplayer`

### 2. 优先级

建立主名字时，建议按下面优先级取值:

1. `Essentials last-account-name`
2. `LuckPerms USERNAME`
3. `XConomy player`
4. `LiteSignIn Name`
5. 其他来源名字

### 3. 冲突处理

如果扫描后出现:

1. 同一个名字对应多个旧 UUID
2. 同一个旧 UUID 对应多个名字

处理规则:

1. 第二种允许存在
2. 第一种默认锁定，禁止玩家直接认领
3. 管理员通过 `admin resolve` 指定唯一旧 UUID

### 4. 离线 UUID 推导

首版不把“通过名字现算离线 UUID”当作主逻辑。

只有在以下条件同时满足时才允许兜底:

1. 配置 `allow-offline-uuid-fallback: true`
2. 索引里没有找到旧 UUID
3. 通过离线算法算出的 UUID 在归档目录里确实存在数据

这样可以避免把 v3 / v4 / 历史混合数据误认错。

## 十四、认领流程设计

### 1. 前置校验

玩家执行 `/uuidmigrate claim <旧用户名>` 后:

1. 检查运行模式是否为 `CLAIM`
2. 检查玩家是否已在迁移中
3. 检查旧用户名是否存在
4. 检查旧用户名是否唯一指向一个旧 UUID
5. 检查旧 UUID 是否已被认领
6. 检查该旧 UUID 是否属于 Floodgate
7. 检查当前正版 UUID 是否已经绑定过别的旧号

### 2. 锁定与下线

校验通过后:

1. 将 `legacy_account.claim_status` 改成 `LOCKED`
2. 记录一个新的 `claim_id`
3. 给玩家加临时封禁
4. 踢出玩家
5. 后台异步开始迁移

### 3. 执行顺序

建议顺序:

1. 建立回滚备份
2. 原版数据
3. `Essentials`
4. `XConomy`
5. `LuckPerms`
6. `PlayerTitle`
7. `PlayerTask`
8. `LiteSignIn`
9. `XyKit`
10. `SimplePlaytime`
11. `HoloMobHealth`
12. `fakeplayer`
13. `Residence`
14. `QuickShop-Hikari`

说明:

1. 共享状态最重的两个适配器放后面
2. 任一适配器失败就触发全局回滚

### 4. 完成收尾

全部成功后:

1. 更新 `claimed_by_uuid`
2. 更新 `claimed_by_name`
3. 更新 `claim_status = CLAIMED`
4. 解除封禁
5. 写出成功日志

### 5. 失败处理

任一步失败:

1. 停止后续适配器
2. 触发回滚
3. 把 `claim_status` 改成 `FAILED`
4. 解除封禁
5. 写出失败报告

## 十五、回滚设计

每次正式认领前，都在:

`plugins/UUIDMigrate/backups/<claim-id>/`

保存当前目标数据备份。

至少备份:

1. 当前玩家的 `world/playerdata/<new-uuid>.dat`
2. 当前玩家的 `world/stats/<new-uuid>.json`
3. 当前玩家的 `world/advancements/<new-uuid>.json`
4. 相关插件记录的迁移前版本
5. `Residence` 与 `QuickShop` 被改动的对象快照

数据库型适配器优先使用事务。

文件型适配器按下面规则:

1. 先备份
2. 再写临时文件
3. 最后原子替换

## 十六、适配器接口设计

统一接口建议:

```java
public interface MigrationAdapter {
    String key();
    boolean isEnabled();
    void scan(ScanContext context) throws Exception;
    void validate(ClaimContext context) throws Exception;
    void backup(ClaimContext context) throws Exception;
    void migrate(ClaimContext context) throws Exception;
    void rollback(ClaimContext context) throws Exception;
}
```

说明:

1. `scan` 阶段负责写索引
2. `validate` 阶段做认领前检查
3. `backup` 阶段给回滚准备材料
4. `migrate` 做真正替换
5. `rollback` 只回滚本适配器改动

## 十七、各适配器设计

### 1. VanillaAdapter

来源:

1. `legacy-data/.../world/playerdata/<old-uuid>.dat`
2. `legacy-data/.../world/stats/<old-uuid>.json`
3. `legacy-data/.../world/advancements/<old-uuid>.json`

写入:

1. `world/playerdata/<new-uuid>.dat`
2. `world/stats/<new-uuid>.json`
3. `world/advancements/<new-uuid>.json`

策略:

1. 直接用旧档替换新正版生成的新空档
2. 不做内容级 NBT 合并
3. 玩家当前新号的初始档只做回滚备份，不参与合并

### 2. EssentialsAdapter

来源:

1. `legacy-data/.../plugins/Essentials/userdata/<old-uuid>.yml`

写入:

1. `plugins/Essentials/userdata/<new-uuid>.yml`

策略:

1. 以旧档为基准复制到新 UUID 文件名
2. 将 `last-account-name` 更新成当前正版名
3. 其他字段保持原样

备注:

1. `money` 跟着旧档走
2. `XConomy` 仍视为主经济

### 3. XConomyAdapter

表:

1. `xconomy`
2. `xconomyrecord`

策略:

1. 把 `UID = old-uuid` 改成 `new-uuid`
2. 把 `player = 旧名` 改成当前正版名
3. 相关流水中出现旧 UUID / 旧名字的字段一起改

### 4. LuckPermsAdapter

功能表:

1. `LUCKPERMS_PLAYERS`
2. `LUCKPERMS_USER_PERMISSIONS`

策略:

1. 改 `UUID`
2. 改 `USERNAME`
3. 保留原权限、主组、上下文

备注:

1. `LUCKPERMS_ACTIONS` 这类审计表不作为首版功能迁移核心

### 5. PlayerTitleAdapter

策略:

1. 改 `player_uuid`
2. 改 `player_name`
3. 保留称号币和已拥有称号

### 6. PlayerTaskAdapter

策略:

1. 改 `player_uuid`
2. 改 `player_name`
3. 当前镜像里重点迁 `task_coin`

### 7. LiteSignInAdapter

策略:

1. 改 `UUID`
2. 改 `Name`
3. 保留连签、补签卡和历史记录

### 8. XyKitAdapter

策略:

1. 把 `players.<old-uuid>` 节点整体迁到 `players.<new-uuid>`
2. 保留 `claimed-kits`

### 9. SimplePlaytimeAdapter

策略:

1. 把旧 UUID 键迁到新 UUID 键
2. 保留在线时长

### 10. HoloMobHealthAdapter

策略:

1. 改 `UUID`
2. 改 `NAME`
3. 保留显示偏好

### 11. fakeplayerAdapter

当前镜像确认到的关键结构:

1. `fake_player_profile(id, name, uuid)`
2. `user_config(id, player_id, key, value)`
3. `fakeplayer_skin(player_id, creator_id, target_id)` 当前镜像无实际记录

首版策略:

1. 不改 `fake_player_profile.uuid`
   - 这是“假人自己的 UUID”，不是玩家归属 UUID
2. 把 `user_config.player_id = old-uuid` 改成 `new-uuid`
3. 如果 `fakeplayer_skin.creator_id` 命中旧玩家 UUID，则改成新正版 UUID
4. `fakeplayer_skin.player_id / target_id` 默认不改

目的:

1. 保证玩家原来控制的假人配置还能归到自己名下
2. 不去改假人实体自身身份

### 12. ResidenceAdapter

扫描来源:

1. `legacy-data/.../plugins/Residence/Save/PlayerData`
2. `legacy-data/.../plugins/Residence/Save/Worlds/*.yml`

准备阶段处理的是活目录:

1. `plugins/Residence/Save/Worlds/*.yml`

准备阶段策略:

1. 扫描归档源，记录 `旧 owner uuid -> 领地列表`
2. 修改活目录世界文件，把这些领地的 `OwnerUUID` 改成托管 UUID
3. 保留原 `OwnerLastKnownName`
4. 不把所有 `PlayerData/<old-uuid>.yml` 合并到托管号

认领阶段策略:

1. 根据索引找到该旧 UUID 名下所有领地
2. 在活目录世界文件中，把托管 UUID 改回 `new-uuid`
3. 把 `OwnerLastKnownName` 改成当前正版名
4. 修正 owner 相关权限字段
5. 从归档源读取旧 `PlayerData/<old-uuid>.yml`
6. 写入或合并到活目录 `PlayerData/<new-uuid>.yml`

### 13. QuickShopHikariAdapter

扫描来源:

1. `legacy-data/.../plugins/QuickShop-Hikari/shops.mv.db`

准备阶段处理的是活目录:

1. `plugins/QuickShop-Hikari/shops.mv.db`

准备阶段策略:

1. 从归档源记录 `旧 owner uuid -> shop id 列表`
2. 在活库中把这些商店 `DATA.OWNER` 改成托管 UUID
3. 在 `PLAYERS` 表准备托管 UUID 对应的缓存名

认领阶段策略:

1. 根据索引找到该旧 UUID 名下所有 shop id
2. 在活库中把这些 `DATA.OWNER` 改成 `new-uuid`
3. 更新 `PLAYERS.UUID`
4. 更新 `PLAYERS.CACHEDNAME`
5. 其他权限字段若命中旧 UUID，一并替换

约束:

1. 过渡期商店交易不追账
2. 重点是保住商店实体和归属链

## 十八、报告设计

`admin report` 至少输出:

1. 旧账号总数
2. 可认领账号数
3. Floodgate 跳过数
4. 重名冲突列表
5. 缺少主名字的账号
6. 缺少核心资产的账号
7. `Residence` 托管数量
8. `QuickShop` 托管数量
9. 归档工具清单是否存在
10. 归档时间和快照名

建议同时生成:

1. `reports/scan-summary-<time>.md`
2. `reports/name-conflicts-<time>.csv`
3. `reports/unclaimed-assets-<time>.csv`

## 十九、实施顺序

为了尽快落地，建议开发顺序如下:

1. 先写 `一键转移工具.bat`
2. 再搭 Paper 插件骨架
3. 先做 `config.yml`、命令注册、`index.db`
4. 先做 `scan` 和 `report`
5. 先做 `VanillaAdapter`
6. 再做 `Essentials`、`XConomy`、`LuckPerms`
7. 再做 `PlayerTitle`、`PlayerTask`、`LiteSignIn`、`XyKit`
8. 再做 `SimplePlaytime`、`HoloMobHealth`
9. 再做 `fakeplayer`
10. 最后做 `Residence` 和 `QuickShop`
11. 最后补 `rollback` 和 `force-claim`

原因:

1. 先把个人资产链路打通
2. 共享状态最复杂的两个放最后
3. 出问题时更容易定位

## 二十、首版验收标准

满足下面这些，就算首版可上线测试:

1. `一键转移工具.bat` 能正确生成归档目录
2. 能扫描 `legacy-data` 并产出索引
3. 能识别并跳过 Floodgate 玩家
4. 能处理重名冲突
5. 玩家可通过命令认领旧号
6. 迁移时会自动封禁并踢下线
7. 原版存档、经济、权限、称号、签到、礼包状态可以迁过去
8. `Residence` 和 `QuickShop` 能先托管、后认领
9. 迁移失败时可回滚
10. 认领成功后同一旧号不能再次认领

## 二十一、编码前不再需要讨论的结论

下面这些已经固定，后面直接按这个写:

1. 旧数据根目录用 `plugins/UUIDMigrate/legacy-data/<snapshot-id>/`
2. Bedrock / Floodgate 首版不动
3. 登录插件不迁，玩家重新注册
4. `Residence` / `QuickShop` 走托管方案
5. 玩家认领时先临时封禁再踢出
6. `SimplePlaytime`、`HoloMobHealth`、`fakeplayer` 也纳入首版
7. 需要提供一个放在 `server.jar` 同级目录的 `一键转移工具.bat`
8. `Residence` 和 `QuickShop` 在归档时采用“复制归档，活数据保留”
