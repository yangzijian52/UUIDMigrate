package cn.uuidmigrate.command;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.config.PluginMode;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.service.ClaimService;
import cn.uuidmigrate.service.ClaimRuntimeStateService;
import cn.uuidmigrate.service.PendingClaimService;
import cn.uuidmigrate.service.PrepareService;
import cn.uuidmigrate.service.ReportService;
import cn.uuidmigrate.service.RollbackService;
import cn.uuidmigrate.service.ScanService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UuidMigrateCommand implements CommandExecutor, TabCompleter {
    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final IndexDatabase indexDatabase;
    private final ScanService scanService;
    private final ReportService reportService;
    private final PrepareService prepareService;
    private final ClaimService claimService;
    private final RollbackService rollbackService;
    private final ClaimRuntimeStateService claimRuntimeStateService;
    private final PendingClaimService pendingClaimService;
    private final AtomicBoolean backgroundTaskRunning = new AtomicBoolean(false);

    public UuidMigrateCommand(
            UUIDMigratePlugin plugin,
            ConfigService configService,
            IndexDatabase indexDatabase,
            ScanService scanService,
            ReportService reportService,
            PrepareService prepareService,
            ClaimService claimService,
            RollbackService rollbackService,
            ClaimRuntimeStateService claimRuntimeStateService,
            PendingClaimService pendingClaimService
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.indexDatabase = indexDatabase;
        this.scanService = scanService;
        this.reportService = reportService;
        this.prepareService = prepareService;
        this.claimService = claimService;
        this.rollbackService = rollbackService;
        this.claimRuntimeStateService = claimRuntimeStateService;
        this.pendingClaimService = pendingClaimService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "status" -> handleStatus(sender);
            case "claim" -> handleClaim(sender, args);
            case "reload" -> handleReload(sender);
            case "admin" -> handleAdmin(sender, args, label);
            default -> {
                sendHelp(sender, label);
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("uuidmigrate.reload")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }

        PluginMode previousMode = configService.config().mode();
        String previousSnapshotId = configService.config().snapshotId();
        try {
            var reloaded = configService.reloadAndValidate();
            sender.sendMessage(color("&aUUIDMigrate 配置已重新加载。"));
            sender.sendMessage(color("&7当前模式: &f" + modeName(reloaded.mode())));
            sender.sendMessage(color("&7快照编号: &f" + reloaded.snapshotId()));
            sender.sendMessage(color("&7旧数据目录: &f" + reloaded.snapshotRoot()));
            sender.sendMessage(color("&7AuthMe DB: &f" + reloaded.authMeSqlitePath()));
            sender.sendMessage(color("&7dry-run: &f" + reloaded.dryRun()));
        } catch (Exception exception) {
            sender.sendMessage(color("&c配置重载失败，已继续使用旧配置。"));
            sender.sendMessage(color("&7仍在使用模式: &f" + modeName(previousMode)));
            sender.sendMessage(color("&7仍在使用快照编号: &f" + previousSnapshotId));
            plugin.getLogger().warning("Config reload failed; keeping previous config: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c只有玩家才能查询迁移状态。"));
            return true;
        }

        if (!player.hasPermission("uuidmigrate.player.status")) {
            player.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }

        player.sendMessage(color("&7当前模式: &f" + modeName(configService.config().mode())));
        player.sendMessage(color("&7快照编号: &f" + configService.config().snapshotId()));

        try {
            var binding = indexDatabase.findClaimedByNewUuid(player.getUniqueId());
            if (binding.isPresent()) {
                var row = binding.get();
                player.sendMessage(color("&a已认领旧账号: &f" + row.primaryName() + " &7(" + row.legacyUuid() + ")"));
                player.sendMessage(color("&7状态: &f" + row.claimStatus() + (row.claimedAt() == null ? "" : " &7时间: &f" + row.claimedAt())));
            } else {
                var latestClaim = indexDatabase.findLatestClaimByNewUuid(player.getUniqueId());
                if (latestClaim.isPresent()) {
                    var row = latestClaim.get();
                    player.sendMessage(color("&e最近一次认领: &f" + row.claimId()));
                    player.sendMessage(color("&7旧 UUID: &f" + row.legacyUuid()));
                    player.sendMessage(color("&7状态: &f" + row.status()));
                    if (row.errorMessage() != null && !row.errorMessage().isBlank()) {
                        player.sendMessage(color("&7错误: &c" + row.errorMessage()));
                    }
                } else {
                    player.sendMessage(color("&e当前正版 UUID 还没有已完成的认领记录。"));
                }
            }
        } catch (Exception exception) {
            player.sendMessage(color("&c查询状态失败: " + exception.getMessage()));
            plugin.getLogger().warning("查询状态失败: " + exception.getMessage());
        }
        return true;
    }

    private boolean handleClaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c只有玩家才能认领旧账号。"));
            return true;
        }

        if (!player.hasPermission("uuidmigrate.player.claim")) {
            player.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(color("&e用法: /uuidmigrate claim <离线服老名字>"));
            player.sendMessage(color("&7你需要先验证离线服旧账号的 AuthMe 密码，不能直接按正版名认领。"));
            return true;
        }
        if (args.length > 2) {
            player.sendMessage(color("&c不要在命令里输入旧账号密码。"));
            player.sendMessage(color("&e用法: /uuidmigrate claim <离线服老名字>"));
            return true;
        }

        if (configService.config().mode() != PluginMode.CLAIM) {
            player.sendMessage(color("&c当前服务器不在 CLAIM 认领模式。"));
            return true;
        }
        if (!ensureWritesAllowed(player)) {
            return true;
        }

        String legacyName = args[1].trim();
        if (legacyName.isEmpty()) {
            player.sendMessage(color("&e用法: /uuidmigrate claim <离线服老名字>"));
            return true;
        }

        pendingClaimService.beginPendingClaim(player, legacyName);
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            sender.sendMessage(color("&e用法: /" + label + " admin <scan|report|prepare|prepare-all|resolve|force-claim|rollback|unlock>"));
            return true;
        }

        String adminSubcommand = args[1].toLowerCase(Locale.ROOT);
        return switch (adminSubcommand) {
            case "scan" -> handleAdminScan(sender);
            case "report" -> handleAdminReport(sender);
            case "prepare" -> handlePrepare(sender, args);
            case "prepare-all" -> handlePrepareAll(sender);
            case "resolve" -> handleResolve(sender, args);
            case "force-claim" -> handleForceClaim(sender, args);
            case "rollback" -> handleRollback(sender, args);
            case "unlock" -> handleUnlock(sender, args);
            default -> {
                sender.sendMessage(color("&c未知的管理员子命令: " + adminSubcommand));
                yield true;
            }
        };
    }

    private boolean handleAdminScan(CommandSender sender) {
        if (!sender.hasPermission("uuidmigrate.admin.scan")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }

        return runBackgroundTask(sender, "扫描", () -> {
            var summary = scanService.runFullScan();
            return List.of(
                    "&a扫描完成。",
                    "&7快照编号: &f" + summary.snapshotId(),
                    "&7账号总数: &f" + summary.totalAccounts(),
                    "&7可认领账号: &f" + summary.claimableAccounts(),
                    "&7名称冲突数: &f" + summary.conflictNameCount(),
                    "&7资产总数: &f" + summary.totalAssets()
            );
        });
    }

    private boolean handlePrepareAll(CommandSender sender) {
        if (!sender.hasPermission("uuidmigrate.admin.scan")
                || !sender.hasPermission("uuidmigrate.admin.report")
                || !sender.hasPermission("uuidmigrate.admin.prepare")) {
            sender.sendMessage(color("&c你没有权限执行完整预处理。"));
            return true;
        }

        if (configService.config().mode() != PluginMode.PREPARE) {
            sender.sendMessage(color("&c当前服务器不在 PREPARE 预处理模式。"));
            return true;
        }
        if (!ensureWritesAllowed(sender)) {
            return true;
        }
        if (!backgroundTaskRunning.compareAndSet(false, true)) {
            sender.sendMessage(color("&e已经有另一个后台任务正在运行。"));
            return true;
        }

        sender.sendMessage(color("&7prepare-all 开始，将按顺序执行 scan -> report -> prepare residence -> prepare quickshop。"));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                runPrepareAllStep(sender, "1/4 扫描索引", () -> {
                    var summary = scanService.runFullScan();
                    return List.of(
                            "&a[1/4] 扫描索引成功。",
                            "&7账号总数: &f" + summary.totalAccounts(),
                            "&7可认领账号: &f" + summary.claimableAccounts(),
                            "&7名称冲突数: &f" + summary.conflictNameCount(),
                            "&7资产总数: &f" + summary.totalAssets()
                    );
                });
                runPrepareAllStep(sender, "2/4 生成报告", () -> {
                    var result = reportService.generateLatestReport();
                    return List.of(
                            "&a[2/4] 生成报告成功。",
                            "&7摘要文件: &f" + result.summaryPath().getFileName(),
                            "&7冲突文件: &f" + result.conflictPath().getFileName(),
                            "&7资产文件: &f" + result.assetPath().getFileName()
                    );
                });
                runPrepareAllStep(sender, "3/4 预处理 Residence", () -> prepareAllPrepareLines("3/4", "residence"));
                runPrepareAllStep(sender, "4/4 预处理 QuickShop", () -> prepareAllPrepareLines("4/4", "quickshop"));
                sendSync(sender, "&aprepare-all 全部完成。");
                sendSync(sender, "&e不会自动切换 CLAIM；请人工修改 config.yml 后执行 /uuidmigrate reload。");
            } catch (Exception exception) {
                plugin.getLogger().warning("prepare-all stopped: " + exception.getMessage());
            } finally {
                backgroundTaskRunning.set(false);
            }
        });
        return true;
    }

    private void runPrepareAllStep(CommandSender sender, String stepName, TaskSupplier supplier) throws Exception {
        sendSync(sender, "&7[" + stepName + "] 开始...");
        try {
            List<String> lines = supplier.get();
            lines.forEach(line -> sendSync(sender, line));
        } catch (Exception exception) {
            sendSync(sender, "&c[" + stepName + "] 失败，prepare-all 已停止: " + exception.getMessage());
            throw exception;
        }
    }

    private List<String> prepareAllPrepareLines(String stepNumber, String target) throws Exception {
        var result = prepareService.runPrepare(target);
        List<String> lines = new ArrayList<>();
        lines.add("&a[" + stepNumber + "] 预处理成功: &f" + prepareTargetName(result.adapterKey()) + "&a。");
        lines.add("&7已索引资产: &f" + result.assetCount());
        lines.add("&7已更新所有者: &f" + result.changedCount());
        lines.add("&7已修改目标数: &f" + result.touchedTargetCount());
        result.notes().stream()
                .map(note -> "&7" + note)
                .forEach(lines::add);
        return lines;
    }

    private boolean handleAdminReport(CommandSender sender) {
        if (!sender.hasPermission("uuidmigrate.admin.report")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }

        return runBackgroundTask(sender, "生成报告", () -> {
            var result = reportService.generateLatestReport();
            return List.of(
                    "&a报告已生成。",
                    "&7账号总数: &f" + result.summary().totalAccounts(),
                    "&7可认领账号: &f" + result.summary().claimableAccounts(),
                    "&7摘要文件: &f" + result.summaryPath().getFileName(),
                    "&7冲突文件: &f" + result.conflictPath().getFileName(),
                    "&7资产文件: &f" + result.assetPath().getFileName()
            );
        });
    }

    private boolean handlePrepare(CommandSender sender, String[] args) {
        if (!sender.hasPermission("uuidmigrate.admin.prepare")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }

        String target = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (!target.equals("residence") && !target.equals("quickshop")) {
            sender.sendMessage(color("&e用法: /uuidmigrate admin prepare <residence|quickshop>"));
            return true;
        }

        if (configService.config().mode() != PluginMode.PREPARE) {
            sender.sendMessage(color("&c当前服务器不在 PREPARE 预处理模式。"));
            return true;
        }
        if (!ensureWritesAllowed(sender)) {
            return true;
        }

        return runBackgroundTask(sender, "预处理/" + prepareTargetName(target), () -> {
            var result = prepareService.runPrepare(target);
            List<String> lines = new ArrayList<>();
            lines.add("&a预处理完成: &f" + prepareTargetName(result.adapterKey()) + "&a。");
            lines.add("&7已索引资产: &f" + result.assetCount());
            lines.add("&7已更新所有者: &f" + result.changedCount());
            lines.add("&7已修改目标数: &f" + result.touchedTargetCount());
            result.notes().stream()
                    .map(note -> "&7" + note)
                    .forEach(lines::add);
            return lines;
        });
    }

    private boolean handleResolve(CommandSender sender, String[] args) {
        if (!sender.hasPermission("uuidmigrate.admin.resolve")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(color("&e用法: /uuidmigrate admin resolve <legacy-name> <legacy-uuid>"));
            return true;
        }
        if (!ensureWritesAllowed(sender)) {
            return true;
        }

        String legacyName = args[2];
        try {
            var latestSnapshotId = indexDatabase.latestSnapshotId()
                    .orElseThrow(() -> new IllegalStateException("当前还没有扫描结果。"));
            if (!latestSnapshotId.equals(configService.config().snapshotId())) {
                sender.sendMessage(color("&c最近一次扫描的快照是 &f" + latestSnapshotId + "&c，但当前配置中的快照是 &f" + configService.config().snapshotId() + "&c，请先运行 /uuidmigrate admin scan。"));
                return true;
            }

            var chosenUuid = java.util.UUID.fromString(args[3]);
            var scanId = indexDatabase.latestScanId()
                    .orElseThrow(() -> new IllegalStateException("当前还没有扫描结果。"));
            var matches = indexDatabase.findNameMatches(scanId, legacyName, ignoredConflictUuids());
            boolean exists = matches.stream().anyMatch(match -> match.legacyUuid().equals(chosenUuid));
            if (!exists) {
                sender.sendMessage(color("&c你提供的 UUID 不在这个名字当前的冲突集合里。"));
                return true;
            }

            indexDatabase.resolveNameConflict(legacyName, chosenUuid, sender.getName());
            sender.sendMessage(color("&a冲突已处理: &f" + legacyName + " &7-> &f" + chosenUuid));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(color("&cUUID 格式不正确: " + args[3]));
        } catch (Exception exception) {
            sender.sendMessage(color("&c处理冲突失败: " + exception.getMessage()));
            plugin.getLogger().warning("处理冲突失败: " + exception.getMessage());
        }
        return true;
    }

    private boolean handleForceClaim(CommandSender sender, String[] args) {
        if (!sender.hasPermission("uuidmigrate.admin.forceclaim")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(color("&e用法: /uuidmigrate admin force-claim <player> <legacy-uuid>"));
            return true;
        }
        if (configService.config().mode() != PluginMode.CLAIM) {
            sender.sendMessage(color("&c当前服务器不在 CLAIM 认领模式。"));
            return true;
        }
        if (!ensureWritesAllowed(sender)) {
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(color("&c玩家不在线: " + args[2]));
            return true;
        }

        try {
            var legacyUuid = java.util.UUID.fromString(args[3]);
            var preview = claimService.preview(target, legacyUuid);
            sender.sendMessage(color("&7匹配到的旧 UUID: &f" + preview.legacyUuid()));
            if (preview.primaryName() != null && !preview.primaryName().isBlank()) {
                sender.sendMessage(color("&7解析出的旧名字: &f" + preview.primaryName()));
            }
            claimService.startForcedClaim(target, legacyUuid);
            sender.sendMessage(color("&a已为 &f" + target.getName() + "&a 启动强制认领。"));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(color("&cUUID 格式不正确: " + args[3]));
        } catch (Exception exception) {
            sender.sendMessage(color("&c强制认领失败: " + exception.getMessage()));
            plugin.getLogger().warning("强制认领失败: " + exception.getMessage());
        }
        return true;
    }

    private boolean handleRollback(CommandSender sender, String[] args) {
        if (!sender.hasPermission("uuidmigrate.admin.rollback")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(color("&e用法: /uuidmigrate admin rollback <claim-id>"));
            return true;
        }
        if (!ensureWritesAllowed(sender)) {
            return true;
        }

        String claimId = args[2];
        return runBackgroundTask(sender, "回滚/" + claimId, () -> {
            var result = rollbackService.rollbackClaim(claimId);
            return List.of(
                    "&a回滚完成。",
                    "&7认领 ID: &f" + result.claimId(),
                    "&7备份目录: &f" + result.backupRoot().getFileName(),
                    "&7报告文件: &f" + result.reportPath().getFileName()
            );
        });
    }

    private boolean handleUnlock(CommandSender sender, String[] args) {
        if (!sender.hasPermission("uuidmigrate.admin.unlock")) {
            sender.sendMessage(color("&c你没有权限执行此操作。"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(color("&e用法: /uuidmigrate admin unlock <legacy-uuid>"));
            return true;
        }
        if (!ensureWritesAllowed(sender)) {
            return true;
        }

        try {
            var legacyUuid = java.util.UUID.fromString(args[2]);
            if (claimRuntimeStateService.isLegacyUuidActive(legacyUuid)) {
                sender.sendMessage(color("&c这个旧账号当前正在迁移中，请等待正在运行的认领完成后再解锁。"));
                return true;
            }

            var result = indexDatabase.unlockLegacyAccount(legacyUuid);
            sender.sendMessage(color("&a旧账号已解锁: &f" + legacyUuid));
            sender.sendMessage(color("&7恢复后的状态: &f" + result.restoredStatus()));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(color("&cUUID 格式不正确: " + args[2]));
        } catch (Exception exception) {
            sender.sendMessage(color("&c解锁失败: " + exception.getMessage()));
            plugin.getLogger().warning("解锁失败: " + exception.getMessage());
        }
        return true;
    }

    private boolean ensureWritesAllowed(CommandSender sender) {
        if (!configService.config().dryRun()) {
            return true;
        }
        sender.sendMessage(color("&e当前插件启用了 dry-run=true，所有写入操作都已禁用。"));
        return false;
    }

    private boolean runBackgroundTask(CommandSender sender, String taskName, TaskSupplier supplier) {
        if (!backgroundTaskRunning.compareAndSet(false, true)) {
            sender.sendMessage(color("&e已经有另一个后台任务正在运行。"));
            return true;
        }

        sender.sendMessage(color("&7" + taskName + "进行中..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> lines = supplier.get();
                Bukkit.getScheduler().runTask(plugin, () -> lines.forEach(line -> sender.sendMessage(color(line))));
            } catch (Exception exception) {
                plugin.getLogger().warning(taskName + "失败: " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(color("&c" + taskName + "失败: " + exception.getMessage())));
            } finally {
                backgroundTaskRunning.set(false);
            }
        });
        return true;
    }

    private void sendSync(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(color(message)));
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(color("&6UUIDMigrate 命令"));
        sender.sendMessage(color("&e/" + label + " status &7- 查看当前玩家的认领状态"));
        sender.sendMessage(color("&e/" + label + " claim <离线服老名字> &7- 验证旧 AuthMe 密码后认领旧账号"));
        sender.sendMessage(color("&e/" + label + " reload &7- 重新读取配置文件"));
        sender.sendMessage(color("&e/" + label + " admin scan &7- 扫描当前快照数据"));
        sender.sendMessage(color("&e/" + label + " admin report &7- 生成扫描报告"));
        sender.sendMessage(color("&e/" + label + " admin prepare <residence|quickshop> &7- 预处理在线服资产归属"));
        sender.sendMessage(color("&e/" + label + " admin prepare-all &7- 按顺序执行 scan/report/prepare residence/prepare quickshop"));
        sender.sendMessage(color("&e/" + label + " admin resolve <legacy-name> <legacy-uuid> &7- 处理名字冲突"));
        sender.sendMessage(color("&e/" + label + " admin force-claim <player> <legacy-uuid> &7- 为在线玩家强制认领"));
        sender.sendMessage(color("&e/" + label + " admin rollback <claim-id> &7- 回滚一次认领"));
        sender.sendMessage(color("&e/" + label + " admin unlock <legacy-uuid> &7- 解锁旧账号"));
    }

    private String color(String message) {
        return message.replace('&', '\u00A7');
    }

    private Set<java.util.UUID> ignoredConflictUuids() {
        return Set.of(
                configService.config().residenceHolderUuid(),
                configService.config().quickshopHolderUuid()
        );
    }

    private String prepareTargetName(String adapterKey) {
        return switch (adapterKey.toLowerCase(Locale.ROOT)) {
            case "residence" -> "Residence 领地";
            case "quickshop" -> "QuickShop 商店";
            default -> adapterKey;
        };
    }

    private String modeName(PluginMode mode) {
        return switch (mode) {
            case PREPARE -> "PREPARE 预处理";
            case CLAIM -> "CLAIM 认领";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "claim", "reload", "admin"), args[0]);
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0])) {
            return filter(List.of("scan", "report", "prepare", "prepare-all", "resolve", "force-claim", "rollback", "unlock"), args[1]);
        }
        if (args.length == 3 && "admin".equalsIgnoreCase(args[0]) && "prepare".equalsIgnoreCase(args[1])) {
            return filter(List.of("residence", "quickshop"), args[2]);
        }
        if (args.length == 3 && "admin".equalsIgnoreCase(args[0]) && "force-claim".equalsIgnoreCase(args[1])) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList(), args[2]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> candidates, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .toList();
    }

    @FunctionalInterface
    private interface TaskSupplier {
        List<String> get() throws Exception;
    }
}
