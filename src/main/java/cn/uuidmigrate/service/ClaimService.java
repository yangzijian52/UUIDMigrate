package cn.uuidmigrate.service;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.adapter.AdapterRegistry;
import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.model.ClaimStatus;
import cn.uuidmigrate.model.ClaimIndexState;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.JsonFileUtil;
import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class ClaimService {
    private static final String CLAIM_INDEX_STATE_FILE = "claim-index-state.json";
    private static final String FLOODGATE_UUID_PREFIX = "00000000-0000-0000-0009-";

    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final IndexDatabase indexDatabase;
    private final AdapterRegistry adapterRegistry;
    private final LoginBlockService loginBlockService;
    private final ClaimRuntimeStateService claimRuntimeStateService;
    private final RollbackService rollbackService;
    private final TameableOwnerMigrationService tameableOwnerMigrationService;
    private final Gson gson = new Gson();

    public ClaimService(
            UUIDMigratePlugin plugin,
            ConfigService configService,
            IndexDatabase indexDatabase,
            AdapterRegistry adapterRegistry,
            LoginBlockService loginBlockService,
            ClaimRuntimeStateService claimRuntimeStateService,
            RollbackService rollbackService,
            TameableOwnerMigrationService tameableOwnerMigrationService
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.indexDatabase = indexDatabase;
        this.adapterRegistry = adapterRegistry;
        this.loginBlockService = loginBlockService;
        this.claimRuntimeStateService = claimRuntimeStateService;
        this.rollbackService = rollbackService;
        this.tameableOwnerMigrationService = tameableOwnerMigrationService;
    }

    public ClaimPreview preview(Player player, String legacyName) throws Exception {
        requireCurrentSnapshotScan();
        String scanId = indexDatabase.latestScanId()
                .orElseThrow(() -> new IllegalStateException("当前没有扫描结果，请先运行 /uuidmigrate admin scan。"));
        List<IndexDatabase.NameMatchRow> matches = indexDatabase.findNameMatches(scanId, legacyName, ignoredLegacyUuids());
        if (matches.isEmpty()) {
            var fallback = offlineFallback(scanId, legacyName);
            if (fallback.isPresent()) {
                return buildPreview(player, scanId, fallback.get().legacyUuid(), legacyName, fallback.get().primaryName(), fallback.get().claimStatus());
            }
            throw new IllegalStateException("没有找到这个旧名字对应的归档账号: " + legacyName);
        }
        if (matches.size() > 1) {
            var resolvedUuid = indexDatabase.findResolvedLegacyUuid(legacyName);
            if (resolvedUuid.isPresent()) {
                matches = matches.stream()
                        .filter(match -> match.legacyUuid().equals(resolvedUuid.get()))
                        .toList();
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("这个旧名字对应多个 UUID，需要管理员先执行 resolve。");
            }
        }

        IndexDatabase.NameMatchRow match = matches.get(0);
        return buildPreview(player, scanId, match.legacyUuid(), legacyName, match.primaryName(), match.claimStatus());
    }

    public ClaimPreview previewSelf(Player player) throws Exception {
        requireJavaPlayer(player);
        return preview(player, player.getName());
    }

    public ClaimPreview preview(Player player, UUID legacyUuid) throws Exception {
        requireCurrentSnapshotScan();
        String scanId = indexDatabase.latestScanId()
                .orElseThrow(() -> new IllegalStateException("当前没有扫描结果，请先运行 /uuidmigrate admin scan。"));
        IndexDatabase.LegacyAccountRow account = indexDatabase.findLegacyAccount(legacyUuid)
                .orElseThrow(() -> new IllegalStateException("索引中找不到这个旧 UUID: " + legacyUuid));
        if (!scanId.equals(account.lastScanId())) {
            throw new IllegalStateException("这个旧 UUID 不属于最近一次扫描结果: " + legacyUuid);
        }

        String preferredName = indexDatabase.findPreferredLegacyName(scanId, legacyUuid)
                .orElse(trimToNull(account.primaryName()));
        return buildPreview(player, scanId, legacyUuid, preferredName, preferredName, account.claimStatus());
    }

    private java.util.Optional<ClaimPreview> offlineFallback(String scanId, String legacyName) throws Exception {
        if (!configService.config().allowOfflineUuidFallback()) {
            return java.util.Optional.empty();
        }

        UUID derivedUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + legacyName).getBytes(StandardCharsets.UTF_8));
        var account = indexDatabase.findLegacyAccount(derivedUuid);
        if (account.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (!scanId.equals(account.get().lastScanId())) {
            return java.util.Optional.empty();
        }
        validateClaimableAccount(account.get());
        return java.util.Optional.of(new ClaimPreview(
                scanId,
                legacyName,
                derivedUuid,
                account.get().primaryName(),
                account.get().claimStatus(),
                account.get().claimedByUuid(),
                account.get().claimedByName(),
                account.get().claimedAt()
        ));
    }

    private void validateClaimableAccount(IndexDatabase.LegacyAccountRow account) {
        if (account.platformType() == PlatformType.JAVA_ONLINE) {
            throw new IllegalStateException("这个账号本身已经是正版 UUID，不需要离线转正版迁移。");
        }
        if (account.platformType() == PlatformType.FLOODGATE && configService.config().skipFloodgatePlayers()) {
            throw new IllegalStateException("当前配置已排除 Floodgate 玩家，不参与 UUID 迁移。");
        }
    }

    private void requireCurrentSnapshotScan() throws Exception {
        String latestSnapshotId = indexDatabase.latestSnapshotId()
                .orElseThrow(() -> new IllegalStateException("当前没有扫描结果，请先运行 /uuidmigrate admin scan。"));
        if (!latestSnapshotId.equals(configService.config().snapshotId())) {
            throw new IllegalStateException("最近一次扫描的快照是 " + latestSnapshotId + "，但当前配置中的快照是 " + configService.config().snapshotId() + "，请先运行 /uuidmigrate admin scan。");
        }
    }

    private Set<UUID> ignoredLegacyUuids() {
        return Set.of(
                configService.config().residenceHolderUuid(),
                configService.config().quickshopHolderUuid()
        );
    }

    public void startClaim(Player player, String legacyName) throws Exception {
        ClaimPreview preview = preview(player, legacyName);
        beginClaim(player, preview);
    }

    public void startSelfClaim(Player player) throws Exception {
        ClaimPreview preview = previewSelf(player);
        beginClaim(player, preview);
    }

    public void startForcedClaim(Player player, UUID legacyUuid) throws Exception {
        requireJavaPlayer(player);
        ClaimPreview preview = preview(player, legacyUuid);
        beginClaim(player, preview);
    }

    private ClaimPreview buildPreview(
            Player player,
            String scanId,
            UUID legacyUuid,
            String requestedLegacyName,
            String primaryName,
            ClaimStatus claimStatus
    ) throws Exception {
        requireJavaPlayer(player);
        if (loginBlockService.isBlocked(player.getUniqueId())) {
            throw new IllegalStateException("This player already has a migration in progress.");
        }

        IndexDatabase.LegacyAccountRow account = indexDatabase.findLegacyAccount(legacyUuid)
                .orElseThrow(() -> new IllegalStateException("Legacy account disappeared from index: " + legacyUuid));
        if (!scanId.equals(account.lastScanId())) {
            throw new IllegalStateException("Legacy UUID is not part of the latest scan: " + legacyUuid);
        }

        validateClaimableAccount(account);
        if (claimStatus == ClaimStatus.CLAIMED && !configService.config().allowRepeatClaim()) {
            throw new IllegalStateException("这个旧账号已经被认领过了。");
        }

        var currentBinding = indexDatabase.findClaimedByNewUuid(player.getUniqueId());
        if (currentBinding.isPresent()) {
            throw new IllegalStateException("当前这个正版账号已经完成过一次认领，不能重复使用 /uuidmigrate claim。");
        }

        String resolvedPrimaryName = trimToNull(primaryName);
        if (resolvedPrimaryName == null) {
            resolvedPrimaryName = indexDatabase.findPreferredLegacyName(scanId, legacyUuid).orElse(null);
        }

        return new ClaimPreview(
                scanId,
                trimToNull(requestedLegacyName) == null ? "" : requestedLegacyName,
                legacyUuid,
                resolvedPrimaryName,
                claimStatus,
                account.claimedByUuid(),
                account.claimedByName(),
                account.claimedAt()
        );
    }

    private void beginClaim(Player player, ClaimPreview preview) throws Exception {
        requireJavaPlayer(player);
        if (configService.config().dryRun()) {
            throw new IllegalStateException("当前启用了 dry-run，不能执行认领写入。");
        }

        UUID newUuid = player.getUniqueId();
        String newName = player.getName();
        String resolvedLegacyName = preview.primaryName() == null || preview.primaryName().isBlank()
                ? preview.legacyName()
                : preview.primaryName();
        ClaimIndexState previousState = new ClaimIndexState(
                preview.claimStatus(),
                preview.previousClaimedByUuid(),
                preview.previousClaimedByName(),
                preview.previousClaimedAt()
        );
        String claimId = buildClaimId(preview.legacyUuid());
        Path backupRoot = configService.config().backupsDirectory().resolve(claimId);

        Files.createDirectories(backupRoot);
        JsonFileUtil.writeJson(
                backupRoot.resolve(CLAIM_INDEX_STATE_FILE),
                gson,
                previousState
        );

        claimId = indexDatabase.tryStartClaim(
                claimId,
                preview.legacyUuid(),
                newUuid,
                newName,
                preview.scanId(),
                configService.config().allowRepeatClaim()
        );
        claimRuntimeStateService.markClaimStarted(claimId, preview.legacyUuid());
        boolean handedOffToWorker = false;
        try {
            ClaimContext context = new ClaimContext(
                    plugin,
                    configService.config(),
                    indexDatabase,
                    preview.scanId(),
                    claimId,
                    resolvedLegacyName,
                    preview.legacyUuid(),
                    newUuid,
                    newName,
                    backupRoot,
                    new ConcurrentHashMap<>()
            );

            Instant until = Instant.now().plusSeconds(configService.config().temporaryBanSeconds());
            loginBlockService.block(newUuid, newName, until, configService.config().kickMessage(), configService.config().banReason());
            try {
                player.sendMessage(configService.config().kickMessage());
                player.kick(Component.text(configService.config().kickMessage()));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> executeClaim(context));
                handedOffToWorker = true;
            } catch (Exception exception) {
                loginBlockService.unblock(newUuid, newName);
                throw exception;
            }
        } catch (Exception exception) {
            try {
                indexDatabase.markClaimFailed(claimId, preview.legacyUuid(), exception.getMessage(), previousState);
            } catch (Exception nested) {
                plugin.getLogger().severe("Failed to record claim bootstrap failure: " + nested.getMessage());
            }
            if (!handedOffToWorker) {
                claimRuntimeStateService.markClaimFinished(claimId, preview.legacyUuid());
            }
            throw exception;
        }
    }

    private void executeClaim(ClaimContext context) {
        List<MigrationAdapter> completedAdapters = new ArrayList<>();
        boolean releaseLoginBlock = false;
        try {
            for (MigrationAdapter adapter : adapterRegistry.adapters()) {
                if (!adapter.isEnabled()) {
                    continue;
                }
                adapter.validate(context);
            }

            for (MigrationAdapter adapter : adapterRegistry.adapters()) {
                if (!adapter.isEnabled()) {
                    continue;
                }
                adapter.backup(context);
            }

            for (MigrationAdapter adapter : adapterRegistry.adapters()) {
                if (!adapter.isEnabled()) {
                    continue;
                }
                completedAdapters.add(adapter);
                adapter.migrate(context);
            }

            indexDatabase.markClaimSucceeded(context.claimId(), context.legacyUuid(), context.newUuid(), context.newName());
            try {
                tameableOwnerMigrationService.afterClaimSucceeded(context);
            } catch (Exception exception) {
                plugin.getLogger().warning("Tameable owner migration failed after claim success: " + exception.getMessage());
            }
            releaseLoginBlock = true;
            plugin.getLogger().info("Claim completed: legacy=" + context.legacyUuid() + " -> new=" + context.newUuid());
        } catch (Exception exception) {
            RollbackService.FailureRollbackResult rollbackResult = rollbackService.rollbackAfterFailure(context, completedAdapters, exception);
            try {
                if (rollbackResult.rollbackErrors().isEmpty()) {
                    indexDatabase.markClaimFailed(context.claimId(), context.legacyUuid(), exception.getMessage(), loadClaimIndexState(context.backupRoot()));
                    releaseLoginBlock = true;
                } else {
                    indexDatabase.markClaimRequiresManualIntervention(
                            context.claimId(),
                            "Automatic rollback needs admin attention: " + exception.getMessage()
                    );
                }
            } catch (Exception nested) {
                plugin.getLogger().severe("Failed to record claim failure: " + nested.getMessage());
            }
            plugin.getLogger().severe("Claim failed for " + context.legacyUuid() + ": " + exception.getMessage());
            plugin.getLogger().severe("Failure report written to: " + rollbackResult.reportPath());
            for (String rollbackError : rollbackResult.rollbackErrors()) {
                plugin.getLogger().severe("Rollback issue: " + rollbackError);
            }
            if (!rollbackResult.rollbackErrors().isEmpty()) {
                plugin.getLogger().severe("Login block is being kept for " + context.newUuid() + " until an admin finishes manual recovery.");
            }
            exception.printStackTrace();
        } finally {
            if (releaseLoginBlock) {
                loginBlockService.unblock(context.newUuid(), context.newName());
            }
            claimRuntimeStateService.markClaimFinished(context.claimId(), context.legacyUuid());
        }
    }

    private ClaimIndexState loadClaimIndexState(Path backupRoot) {
        try {
            Path statePath = backupRoot.resolve(CLAIM_INDEX_STATE_FILE);
            if (!Files.exists(statePath)) {
                return null;
            }
            return JsonFileUtil.readJson(statePath, gson, ClaimIndexState.class);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to read claim index state from " + backupRoot + ": " + exception.getMessage());
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildClaimId(UUID legacyUuid) {
        return "claim-" + Instant.now().toEpochMilli() + "-" + legacyUuid;
    }

    private void requireJavaPlayer(Player player) {
        if (isFloodgatePlayer(player)) {
            throw new IllegalStateException("基岩玩家不能使用 /uuidmigrate claim。基岩玩家应继续使用原有 Floodgate UUID 数据。");
        }
    }

    private boolean isFloodgatePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (uuid != null && uuid.toString().regionMatches(true, 0, FLOODGATE_UUID_PREFIX, 0, FLOODGATE_UUID_PREFIX.length())) {
            return true;
        }

        String playerName = player.getName();
        if (playerName != null) {
            for (String prefix : configService.config().floodgatePrefixes()) {
                if (prefix != null && !prefix.isBlank() && playerName.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            }
        }

        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            Object api = getInstance.invoke(null);
            if (api == null) {
                return false;
            }
            Object result = isFloodgatePlayer.invoke(api, uuid);
            return result instanceof Boolean booleanResult && booleanResult;
        } catch (Exception ignored) {
            return false;
        }
    }

    public record ClaimPreview(
            String scanId,
            String legacyName,
            UUID legacyUuid,
            String primaryName,
            ClaimStatus claimStatus,
            String previousClaimedByUuid,
            String previousClaimedByName,
            String previousClaimedAt
    ) {
    }
}
