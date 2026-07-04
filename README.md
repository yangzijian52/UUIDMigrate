# UUIDMigrate

UUIDMigrate 是一个用于 Minecraft Paper 服务器的离线 UUID 转正版 UUID 数据迁移插件。

插件的目标场景是：原服务器曾经是离线登录服，玩家数据、领地、商店、经济等资产使用离线 UUID 保存；服务器切换为正版验证后，需要让正版玩家通过旧账号名称和旧 AuthMe 密码认领并迁移旧数据。

当前构建文件：

```text
dist/UUIDMigrate-0.1.0-.jar
```

运行要求：

- Paper API: `26.2`
- Java: `21`
- 服务器需要先完成离线服数据快照归档，再进入认领阶段
- 正版服务器内可以不安装 AuthMe，但需要把旧 AuthMe 的 SQLite 数据库放到本插件目录

## 支持的数据

当前插件内置以下适配器：

- Vanilla 玩家数据
- Essentials 用户数据
- XConomy
- LuckPerms
- Residence
- QuickShop-Hikari
- PlayerTitle
- PlayerTask
- LiteSignIn
- XyKit
- SimplePlaytime
- HoloMobHealth
- fakeplayer

Vanilla 玩家数据同时支持旧版路径和 Paper 26.2 路径。

旧版路径：

```text
world/playerdata
world/stats
world/advancements
```

Paper 26.2 路径：

```text
world/players/data
world/players/stats
world/players/advancements
```

## 文件放置

把插件 jar 放到服务器插件目录：

```text
plugins/UUIDMigrate-0.1.0-.jar
```

第一次启动后会生成插件目录：

```text
plugins/UUIDMigrate
```

旧 AuthMe 数据库默认放在：

```text
plugins/UUIDMigrate/authme.db
```

如果你在正版服务器里不安装登录插件，只要把旧服的 `authme.db` 放到这个位置即可。玩家认领时，UUIDMigrate 会读取该 SQLite 数据库校验旧账号密码。

不要让玩家把 AuthMe 密码输入到命令里。正确流程是先执行 `/uuidmigrate claim <旧离线服名字>`，然后在 60 秒内直接发送旧 AuthMe 密码。插件会拦截这条聊天消息，不让它进入公屏。

## 一键归档工具

仓库内提供：

```text
一键转移工具.bat
```
```
注：一键转移工具.bat必须放在服务器根目录
```
使用前必须关闭服务器。

工具会把离线 UUID 数据归档到：

```text
plugins/UUIDMigrate/legacy-data/<snapshot-id>
```

运行时需要输入 `snapshot-id`。这个值必须和 `plugins/UUIDMigrate/config.yml` 里的 `snapshot-id` 完全一致。

建议命名示例：

```text
2026-04-12-before-online-switch
```

归档完成后，目录结构类似：

```text
plugins/UUIDMigrate/
  authme.db
  config.yml
  legacy-data/
    2026-04-12-before-online-switch/
      archive-manifest-*.txt
      world/
      plugins/
```

`archive-manifest-*.txt` 用于确认本次快照由归档工具生成。默认配置会检查该标记。

## 配置说明

主要配置文件：

```text
plugins/UUIDMigrate/config.yml
```

核心配置项：

```yaml
mode: PREPARE
legacy-root: plugins/UUIDMigrate/legacy-data
snapshot-id: 2026-04-12-before-online-switch
dry-run: false
log-detail: true
```

`mode` 有两个值：

- `PREPARE`：管理员扫描、生成报告、预处理 Residence 和 QuickShop。
- `CLAIM`：玩家进入认领阶段，可以执行 `/uuidmigrate claim <旧名>`。

`snapshot-id` 必须和一键归档工具输入的快照编号一致。

`dry-run: true` 时，只允许扫描等非写入操作，不允许 prepare、claim、rollback、unlock 等写入操作。正式迁移时通常设置为 `false`。

AuthMe 校验配置：

```yaml
authme-claim-verification:
  sqlite-path: authme.db
  table: authme
  username-column: username
  password-column: password
  pending-timeout-seconds: 60
```

`sqlite-path: authme.db` 表示读取：

```text
plugins/UUIDMigrate/authme.db
```

如果写成 `plugins/UUIDMigrate/authme.db`，则按服务器根目录解析。

Residence 和 QuickShop 预处理占位 UUID：

```yaml
residence-holder:
  uuid: "11111111-1111-1111-1111-111111111111"
  name: "UUIDMigrate_Residence"

quickshop-holder:
  uuid: "22222222-2222-2222-2222-222222222222"
  name: "UUIDMigrate_Shop"
```

这些占位 UUID 用于 prepare 阶段临时接管资产，claim 成功后再迁移给玩家正版 UUID。

适配器开关：

```yaml
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

不需要迁移某类数据时，可以把对应项改为 `false`。

## 推荐迁移流程

### 1. 关闭服务器

归档旧数据前必须关闭服务器，避免文件写入中途变化。

### 2. 放入插件 jar

把 `dist/UUIDMigrate-0.1.0-.jar` 放入：

```text
plugins/UUIDMigrate-0.1.0-.jar
```

### 3. 放入 AuthMe 数据库

把旧服 AuthMe SQLite 数据库放入：

```text
plugins/UUIDMigrate/authme.db
```

如果目录还不存在，可以先启动一次服务器生成插件目录，也可以手动创建目录。

### 4. 运行一键归档工具

关闭服务器状态下运行：
```
注：一键转移工具.bat必须放在服务器根目录
```
```text
一键转移工具.bat
```

输入 `snapshot-id` 并确认执行。

### 5. 启动服务器，保持 PREPARE 模式

确认 `config.yml`：

```yaml
mode: PREPARE
snapshot-id: <刚才归档时输入的 snapshot-id>
```

启动服务器后执行：

```text
/uuidmigrate admin prepare-all
```

该命令会按固定顺序执行：

```text
scan -> report -> prepare residence -> prepare quickshop
```

### 6. 检查报告

报告输出目录：

```text
plugins/UUIDMigrate/reports
```

重点检查：

- `scan-summary-*.md`
- `name-conflicts-*.csv`
- `unclaimed-assets-*.csv`

如果报告里有名称冲突，需要先用管理员命令 resolve。

### 7. 切换到 CLAIM 模式

修改：

```yaml
mode: CLAIM
```

然后执行：

```text
/uuidmigrate reload
```

确认控制台或游戏内输出当前模式为 `CLAIM`。

### 8. 玩家认领

玩家使用正版账号进入服务器，执行：

```text
/uuidmigrate claim <旧离线服名字>
```

插件会提示玩家在聊天框直接输入旧 AuthMe 密码。密码消息会被插件取消传播，不会进入公屏。

密码错误时：

- 不创建 claim
- 不迁移数据
- 不封禁玩家

密码正确时：

- 插件开始 claim
- 玩家会被临时踢下线并短暂阻止登录
- 后台迁移旧 UUID 数据到正版 UUID
- 完成后玩家重新进服

## 自动认领提示

在 `CLAIM` 模式下，如果玩家进服时满足以下条件：

- 玩家当前正版名匹配旧账号历史名称
- 该历史名称只对应一个可认领旧账号
- 该旧账号未被认领、未锁定
- 当前正版 UUID 尚未完成过认领

插件会发送可点击提示：

```text
检测到您未进行数据转移，请点击这里进行转移。
```

其中“这里”可点击，等价于执行：

```text
/uuidmigrate claim <旧离线服名字>
```

如果名称冲突或无法唯一确定旧账号，插件不会自动提示，避免误认领。

## 指令说明

主命令：

```text
/uuidmigrate
```

别名：

```text
/umigrate
```

### 玩家指令

#### /uuidmigrate status

查看当前玩家的迁移状态。

输出内容包括：

- 当前插件模式
- 当前快照编号
- 当前正版 UUID 是否已经绑定旧账号
- 最近一次 claim 记录及失败原因

权限：

```text
uuidmigrate.player.status
```

默认：所有玩家。

#### /uuidmigrate claim <旧离线服名字>

开始认领旧离线服账号。

执行后插件会进入 pending 状态，等待玩家在聊天框输入旧 AuthMe 密码。

注意：

- 只能玩家执行，控制台不能执行。
- 只能在 `CLAIM` 模式执行。
- 不要在命令中输入密码。
- 命令只能带一个旧名字参数，多余参数会被拒绝。
- 旧名字可以是扫描到的历史名称，不一定必须是当前正版名。

权限：

```text
uuidmigrate.player.claim
```

默认：所有玩家。

### 通用管理指令

#### /uuidmigrate reload

重新读取 `config.yml`。

常用于：

- 从 `PREPARE` 切换到 `CLAIM`
- 修改 `snapshot-id`
- 修改 AuthMe SQLite 路径
- 修改 adapter 开关
- 修改 dry-run

如果配置重载失败，插件会继续使用旧配置，并在日志里记录失败原因。

权限：

```text
uuidmigrate.reload
```

默认：OP。

### 管理员指令

#### /uuidmigrate admin scan

扫描当前快照数据，建立旧账号、历史名称和资产索引。

读取目录：

```text
plugins/UUIDMigrate/legacy-data/<snapshot-id>
```

结果写入：

```text
plugins/UUIDMigrate/index.db
```

输出内容包括：

- 账号总数
- 可认领账号数
- 名称冲突数
- 资产总数

权限：

```text
uuidmigrate.admin.scan
```

默认：OP。

#### /uuidmigrate admin report

基于最近一次 scan 生成报告文件。

输出目录：

```text
plugins/UUIDMigrate/reports
```

生成文件：

```text
scan-summary-*.md
name-conflicts-*.csv
unclaimed-assets-*.csv
```

报告会列出：

- 快照编号
- 可认领账号
- 名称冲突
- 未认领资产
- 缺失主名称账号
- 缺失核心资产账号
- Residence prepare 状态
- QuickShop prepare 状态
- 归档 manifest 状态

权限：

```text
uuidmigrate.admin.report
```

默认：OP。

#### /uuidmigrate admin prepare <residence|quickshop>

单独预处理 Residence 或 QuickShop。

只允许在 `PREPARE` 模式执行。

Residence prepare 会把旧 UUID 相关领地资产预处理到占位 UUID，方便后续 claim 时从占位 UUID 转给正版 UUID。

QuickShop prepare 会把旧 UUID 相关商店资产预处理到占位 UUID，方便后续 claim 时转给正版 UUID。

QuickShop 优先使用 QuickShop-Hikari 托管连接访问在线数据库，正常日志里应看到：

```text
QuickShop 连接来源: quickshop-managed
```

如果出现 direct H2、H2 lock、数据库锁定等信息，需要先停服或处理 QuickShop 数据库占用问题。

权限：

```text
uuidmigrate.admin.prepare
```

默认：OP。

#### /uuidmigrate admin prepare-all

执行完整预处理流程。

只允许在 `PREPARE` 模式执行。

执行顺序固定为：

```text
scan -> report -> prepare residence -> prepare quickshop
```

该命令成功后不会自动切换到 `CLAIM`。需要人工修改 `config.yml`：

```yaml
mode: CLAIM
```

然后执行：

```text
/uuidmigrate reload
```

权限要求：

```text
uuidmigrate.admin.scan
uuidmigrate.admin.report
uuidmigrate.admin.prepare
```

默认：OP。

#### /uuidmigrate admin resolve <legacy-name> <legacy-uuid>

处理名称冲突。

当同一个历史名称对应多个旧 UUID 时，玩家不能直接 claim。管理员需要根据报告确认正确的旧 UUID，然后执行：

```text
/uuidmigrate admin resolve Steve 00000000-0000-0000-0000-000000000000
```

执行后，该历史名称会固定解析到指定旧 UUID。

权限：

```text
uuidmigrate.admin.resolve
```

默认：OP。

#### /uuidmigrate admin force-claim <player> <legacy-uuid>

为在线玩家强制发起旧 UUID 认领。

只允许在 `CLAIM` 模式执行。

用途：

- 玩家旧名字无法唯一匹配
- 管理员已经人工确认旧 UUID
- 需要绕过名称匹配流程，直接指定旧 UUID

注意：

- `<player>` 必须在线。
- 仍会走迁移流程。
- 会检查当前玩家是否已经认领过。

权限：

```text
uuidmigrate.admin.forceclaim
```

默认：OP。

#### /uuidmigrate admin rollback <claim-id>

回滚一次 claim。

claim 过程会在以下目录创建备份：

```text
plugins/UUIDMigrate/backups/<claim-id>
```

如果迁移错误，可以用 claim-id 回滚。

回滚完成后会生成报告，说明恢复了哪些文件和索引状态。

权限：

```text
uuidmigrate.admin.rollback
```

默认：OP。

#### /uuidmigrate admin unlock <legacy-uuid>

解锁旧账号。

用途：

- claim 中断后旧账号处于 `LOCKED`
- 管理员确认没有后台迁移任务仍在运行
- 需要让旧账号重新进入可认领状态

限制：

- 正在迁移中的旧 UUID 不能 unlock。
- 已经成功认领的账号不能用 unlock 当作回滚。
- 已经成功认领需要使用 rollback。

权限：

```text
uuidmigrate.admin.unlock
```

默认：OP。

## 权限汇总

```text
uuidmigrate.player.status       默认 true
uuidmigrate.player.claim        默认 true
uuidmigrate.reload              默认 OP
uuidmigrate.admin.scan          默认 OP
uuidmigrate.admin.report        默认 OP
uuidmigrate.admin.prepare       默认 OP
uuidmigrate.admin.resolve       默认 OP
uuidmigrate.admin.forceclaim    默认 OP
uuidmigrate.admin.rollback      默认 OP
uuidmigrate.admin.unlock        默认 OP
```

## 运行产物

插件运行后会使用以下目录：

```text
plugins/UUIDMigrate/index.db
plugins/UUIDMigrate/legacy-data
plugins/UUIDMigrate/reports
plugins/UUIDMigrate/backups
plugins/UUIDMigrate/logs
```

说明：

- `index.db`：扫描索引和认领记录。
- `legacy-data`：一键归档工具生成的旧数据快照。
- `reports`：scan/report/prepare 输出。
- `backups`：claim、prepare、rollback 的备份。
- `logs`：插件相关日志输出目录。

## 安全注意事项

- 归档旧数据前必须关闭服务器。
- 不要把 AuthMe 密码写进命令。
- 不要把真实 AuthMe 密码发给管理员或机器人。
- `CLAIM` 模式前必须先完成 scan、report、prepare。
- `prepare-all` 不会自动切换 `CLAIM`，必须人工确认后修改配置。
- 迁移前建议保留完整服务器备份。
- QuickShop 数据库被占用时不要强行迁移，先解决锁库。
- 当前插件面向一次性迁移流程，不建议作为长期常驻管理工具随意修改配置。

## 常见问题

### 正版服不安装 AuthMe 可以吗？

可以。把旧服的 AuthMe SQLite 数据库放到：

```text
plugins/UUIDMigrate/authme.db
```

玩家 claim 时，插件会直接读取这个数据库校验旧账号密码。

### 玩家输入密码会不会进公屏？

正常不会。玩家执行 `/uuidmigrate claim <旧名>` 后，下一条聊天消息会被插件拦截并取消传播，用于 AuthMe 密码校验。

### 密码错误会不会迁移数据？

不会。密码错误时不会创建 claim，不会封禁，不会迁移。

### 为什么玩家进服没有自动提示？

自动提示需要满足唯一匹配条件。以下情况不会提示：

- 当前不是 `CLAIM` 模式
- 玩家已经完成过认领
- 玩家名没有匹配历史名称
- 同名历史账号有冲突
- 旧账号已经 CLAIMED 或 LOCKED
- 旧账号被配置为跳过，例如 Floodgate 玩家

### Paper 26.2 玩家数据没迁移怎么办？

确认一键归档工具和插件版本是当前版本。当前版本已经支持：

```text
world/players/data
world/players/stats
world/players/advancements
```

如果旧快照是在旧工具下生成的，里面可能没有这些目录，需要从干净服务器数据重新运行一键归档工具，再重新执行 prepare 流程。

### QuickShop 出现 H2 锁库怎么办？

优先确认服务器是否关闭或 QuickShop 是否仍持有数据库连接。正常在线 prepare 应使用 QuickShop-Hikari 托管连接，日志应显示 `quickshop-managed`。如果回退到 direct H2 或出现 lock，需要先处理数据库占用问题。
