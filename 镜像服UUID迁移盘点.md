# UUIDMigrate 最后方案

时间: 2026-04-11

镜像服目录: `D:\1.21.10\1.21.10`

这份文档只保留最终准备落地的方案，不再记录前面的排查过程。

## 一、方案目标

本次只处理一件事:

- 把 Java 离线服玩家的数据，迁到 Java 正版 UUID

本次明确不处理:

- Floodgate / Bedrock 玩家数据
- 登录系统账号迁移

也就是说，切正版以后:

1. Java 玩家先直接用正版账号进服
2. 需要的话重新注册新号
3. 在游戏内执行迁移指令
4. 插件把他的旧离线数据转到现在这个正版 UUID 上

补充:

1. 需要提供一个放在 `server.jar` 同级目录的一键归档工具 `一键转移工具.bat`
2. 这个工具负责把首版要迁移的旧数据，自动整理到 `plugins/UUIDMigrate/legacy-data/<snapshot-id>/`

## 二、旧数据归档目录

建议默认目录:

`plugins/UUIDMigrate/legacy-data/<snapshot-id>/`

例如:

`plugins/UUIDMigrate/legacy-data/2026-04-11-before-online-switch/`

要求:

1. 目录可配置
2. 保留原始相对路径
3. 插件只从这个归档目录读取旧数据
4. 切正版前，先把需要迁移的旧数据移到这里
5. 推荐通过 `一键转移工具.bat` 自动完成归档

建议结构:

```text
plugins/UUIDMigrate/legacy-data/2026-04-11-before-online-switch/
  world/playerdata/
  world/stats/
  world/advancements/
  plugins/Essentials/userdata/
  plugins/Residence/Save/
  plugins/QuickShop-Hikari/
  plugins/XConomy/playerdata/
  plugins/PlayerTitle/
  plugins/PlayerTask/
  plugins/LiteSignIn/
  plugins/LuckPerms/
  plugins/SimplePlaytime/
  plugins/HoloMobHealth/
  plugins/XyKit/
  plugins/fakeplayer/
```

## 三、首版明确要迁移的数据

这些都纳入首版正式迁移范围，不再区分“建议归档”。

1. 原版玩家数据
   - `world/playerdata`
   - `world/stats`
   - `world/advancements`

2. `Essentials`
   - `plugins/Essentials/userdata`
   - 主要是 home、money、last-account-name、位置类数据

3. `XConomy`
   - `plugins/XConomy/playerdata/data.db`
   - 主经济数据

4. `LuckPerms`
   - `plugins/LuckPerms/luckperms-h2-v2.mv.db`
   - 玩家组、个人权限、主组

5. `Residence`
   - `plugins/Residence/Save/PlayerData`
   - `plugins/Residence/Save/Worlds/*.yml`
   - 领地归属和玩家相关配置

6. `QuickShop-Hikari`
   - `plugins/QuickShop-Hikari/shops.mv.db`
   - 商店拥有者和玩家缓存名

7. `PlayerTitle`
   - `plugins/PlayerTitle/PlayerTitle.db`
   - 称号币、已拥有称号

8. `PlayerTask`
   - `plugins/PlayerTask/PlayerTask.db`
   - 当前镜像里主要是任务币

9. `LiteSignIn`
   - `plugins/LiteSignIn/Database.db`
   - 连签、补签卡、签到记录

10. `XyKit`
   - `plugins/XyKit/data.yml`
   - 已领取礼包状态

11. `SimplePlaytime`
   - `plugins/SimplePlaytime/data.json`
   - 在线时长

12. `HoloMobHealth`
   - `plugins/HoloMobHealth/database.db`
   - 玩家显示偏好

13. `fakeplayer`
   - `plugins/fakeplayer/data.db`
   - 把属于旧玩家的假人资料和相关配置迁到新正版 UUID
   - 首版按玩家私人资产处理

## 四、明确不迁移的数据

这些已经按当前结论排除，不纳入首版自动迁移。

1. `Floodgate` / Bedrock 玩家数据
   - 先不动
   - 继续按原来的 Floodgate UUID 使用

2. `AuthMe`
   - 不迁移
   - 玩家直接重新注册
   - 后续可以考虑直接移除

3. `CoreProtect`
   - 不迁移
   - 只是审计/回档数据

4. `Images`
   - 不迁移
   - 你自己的内容

5. `Citizens`
   - 不迁移
   - 你自己的内容

6. `TAB`
   - 不迁移
   - 当前没有单独给玩家做持久化设置

## 五、玩家识别规则

首版插件只迁移 Java 离线玩家。

规则:

1. 玩家用正版账号进入服务器
2. 插件根据他要认领的旧离线用户名，去归档目录里查旧数据
3. 找到旧用户名对应的旧离线 UUID
4. 把这个旧 UUID 对应的所有资产，迁到当前登录的正版 UUID

补充规则:

1. 旧用户名只能被认领一次
2. 已认领的旧账号要写入插件自己的索引库
3. 不能只靠“当前名字相同”自动合并
4. Floodgate UUID 不进入这套流程

## 六、Residence 和 QuickShop 的最终处理方式

这个方案可行，我建议就按这个做。

核心思路:

1. 切正版前，先把老领地和老商店挂到插件托管身份下面
2. 玩家来认领时，再从托管身份转给他的新正版 UUID

这样做的好处:

1. 领地保护不会出现空窗
2. 商店不会整批失效
3. 玩家没来认领之前，数据也不会丢
4. 插件实现上更稳

### 1. Residence

托管阶段:

1. 把领地 `OwnerUUID` 改成固定托管 UUID
2. 保留原 `OwnerLastKnownName`
3. 额外记录一份插件自己的映射:
   - `旧 owner uuid -> 领地列表`
   - `领地名 -> 旧 owner uuid`

玩家认领阶段:

1. 把属于他的领地从托管 UUID 改成新正版 UUID
2. 把 `OwnerLastKnownName` 改成他当前正版名
3. 同步修正与 owner 相关的权限字段

### 2. QuickShop-Hikari

托管阶段:

1. 把 `DATA.OWNER` 改成固定托管 UUID
2. 在插件索引里记录:
   - `旧 owner uuid -> shop id 列表`
3. `PLAYERS` 表插入或复用托管 UUID 对应的显示名

玩家认领阶段:

1. 把属于他的商店 `DATA.OWNER` 改成新正版 UUID
2. 更新 `PLAYERS.UUID`
3. 更新 `PLAYERS.CACHEDNAME`
4. 如果有写死旧 UUID 的权限字段，也一起替换

### 3. 托管期间的原则

1. 商店在过渡期发生交易，不追账
2. 重点是保证店和领地还在
3. 托管 UUID 必须固定写进配置，不能每次随机
4. 插件必须有自己的索引库，不能只靠改 owner 字段

## 七、玩家迁移流程

### 1. 切正版前

1. 备份整服
2. 运行 `一键转移工具.bat`
3. 把本次需要迁移的旧数据整理到 `legacy-data`
4. Residence 和 QuickShop 先做托管接管
5. 生成插件索引库
6. 检查哪些旧账号已经可以被认领

### 2. 切正版后

1. 玩家用正版账号进服
2. 玩家执行迁移指令
3. 插件根据旧用户名找到旧离线 UUID
4. 插件校验这个旧账号是否已被认领
5. 给玩家临时封禁 5 分钟，并提示“正在迁移数据”
6. 立即踢出玩家
7. 开始替换旧 UUID -> 新正版 UUID
8. 替换完成后取消临时封禁
9. 玩家重新进入服务器

### 3. 为什么要先封禁再踢出

因为玩家在线时替换原版存档和部分插件数据，容易被上线覆盖。

所以这里按你的想法，直接做成:

1. 先封禁 5 分钟
2. 再踢出
3. 离线完成迁移
4. 提前解除封禁

## 八、一键转移工具.bat

位置:

1. 放在和 `server.jar` 同级的服务端根目录

作用:

1. 自动创建 `plugins/UUIDMigrate/legacy-data/<snapshot-id>/`
2. 自动把首版要迁移的数据整理进去
3. 自动创建目录结构
4. 自动输出归档日志

执行原则:

1. 必须在服务器关闭时运行
2. 默认按相对路径工作，不依赖固定盘符
3. 默认快照名可手工输入，也可以用当天日期生成

归档策略:

1. 纯个人数据直接移动到 `legacy-data`
2. `Residence` 和 `QuickShop-Hikari` 因为还要做托管接管，归档时保留活目录，归档源使用复制

也就是说:

1. `world/playerdata`
2. `world/stats`
3. `world/advancements`
4. `Essentials`
5. `XConomy`
6. `PlayerTitle`
7. `PlayerTask`
8. `LiteSignIn`
9. `LuckPerms`
10. `SimplePlaytime`
11. `HoloMobHealth`
12. `XyKit`
13. `fakeplayer`

这些默认直接移动到归档目录。

而下面两项:

1. `Residence`
2. `QuickShop-Hikari`

默认复制到归档目录，同时保留当前活数据给后续托管流程使用。

## 九、插件自身必须保存的数据

`UUIDMigrate` 不能只改外部插件的数据，还要维护自己的索引。

至少需要:

1. `旧用户名 -> 旧离线 UUID`
2. `旧离线 UUID -> 已归档的数据项`
3. `旧离线 UUID -> 是否已认领`
4. `旧离线 UUID -> 新正版 UUID`
5. `旧离线 UUID -> Residence 列表`
6. `旧离线 UUID -> QuickShop 列表`
7. 操作日志和失败回滚记录

## 十、首版我建议的配置项

至少应有:

1. `legacy-root`
2. `snapshot-id`
3. `skip-floodgate-players`
4. `temporary-ban-seconds`
5. `residence-holder-uuid`
6. `quickshop-holder-uuid`
7. `allow-repeat-claim`
8. `dry-run`
9. `log-detail`
