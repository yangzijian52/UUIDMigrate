package cn.uuidmigrate.service;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.adapter.AdapterRegistry;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.model.NameConflict;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.model.ScanSummary;
import cn.uuidmigrate.util.ArchiveManifestUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ReportService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final IndexDatabase indexDatabase;
    private final AdapterRegistry adapterRegistry;

    public ReportService(UUIDMigratePlugin plugin, ConfigService configService, IndexDatabase indexDatabase, AdapterRegistry adapterRegistry) {
        this.plugin = plugin;
        this.configService = configService;
        this.indexDatabase = indexDatabase;
        this.adapterRegistry = adapterRegistry;
    }

    public ReportResult generateLatestReport() throws Exception {
        String latestSnapshotId = indexDatabase.latestSnapshotId()
                .orElseThrow(() -> new IllegalStateException("当前没有扫描结果，请先运行 /uuidmigrate admin scan。"));
        if (!latestSnapshotId.equals(configService.config().snapshotId())) {
            throw new IllegalStateException("最近一次扫描的快照是 " + latestSnapshotId + "，但当前配置中的快照是 " + configService.config().snapshotId() + "，请先运行 /uuidmigrate admin scan。");
        }

        String scanId = indexDatabase.latestScanId()
                .orElseThrow(() -> new IllegalStateException("当前没有扫描结果，请先运行 /uuidmigrate admin scan。"));

        ScanSummary summary = indexDatabase.loadSummary(
                scanId,
                configService.config().skipFloodgatePlayers(),
                configService.config().allowRepeatClaim(),
                ignoredConflictUuids()
        );
        List<NameConflict> conflicts = indexDatabase.loadNameConflicts(scanId, ignoredConflictUuids());
        List<SourceCheck> sourceChecks = collectSourceChecks();
        List<IndexDatabase.AssetRow> unclaimedAssets = indexDatabase.loadUnclaimedAssets(scanId);
        Optional<ArchiveManifestUtil.ArchiveManifest> archiveManifest = ArchiveManifestUtil.loadForSnapshot(configService.config());
        int floodgateSkippedCount = configService.config().skipFloodgatePlayers()
                ? indexDatabase.countAccountsByPlatform(scanId, PlatformType.FLOODGATE)
                : 0;
        List<IndexDatabase.AccountSummaryRow> missingPrimaryNameAccounts = indexDatabase.loadAccountsMissingPrimaryName(scanId);
        List<IndexDatabase.AccountSummaryRow> missingCoreAssetAccounts = indexDatabase.loadAccountsMissingAssetsForAdapters(scanId, requiredAdapterKeys());
        PrepareStatus residencePrepareStatus = loadPrepareStatus("residence", scanId);
        PrepareStatus quickshopPrepareStatus = loadPrepareStatus("quickshop", scanId);

        String timestamp = LocalDateTime.now().format(FILE_TIME);
        Path reportsDir = configService.config().reportsDirectory();
        Files.createDirectories(reportsDir);

        Path summaryPath = reportsDir.resolve("scan-summary-" + timestamp + ".md");
        Path conflictPath = reportsDir.resolve("name-conflicts-" + timestamp + ".csv");
        Path assetPath = reportsDir.resolve("unclaimed-assets-" + timestamp + ".csv");

        Files.writeString(summaryPath, buildSummaryMarkdown(
                summary,
                conflicts,
                sourceChecks,
                archiveManifest,
                floodgateSkippedCount,
                missingPrimaryNameAccounts,
                missingCoreAssetAccounts,
                residencePrepareStatus,
                quickshopPrepareStatus
        ));
        Files.writeString(conflictPath, buildConflictCsv(conflicts));
        Files.writeString(assetPath, buildUnclaimedAssetCsv(unclaimedAssets));

        plugin.getLogger().info("已生成扫描报告集: " + scanId);
        return new ReportResult(summary, summaryPath, conflictPath, assetPath, sourceChecks, archiveManifest.isPresent());
    }

    private List<SourceCheck> collectSourceChecks() {
        List<SourceCheck> checks = new ArrayList<>();
        for (MigrationAdapter adapter : adapterRegistry.adapters()) {
            if (!adapter.isEnabled()) {
                continue;
            }
            for (PathExpectation expectation : adapter.expectedSources()) {
                Path target = configService.config().snapshotRoot().resolve(expectation.relativePath()).normalize();
                checks.add(new SourceCheck(
                        expectation.adapterKey(),
                        expectation.label(),
                        expectation.relativePath(),
                        expectation.required(),
                        Files.exists(target)
                ));
            }
        }
        checks.sort(Comparator.comparing(SourceCheck::adapterKey).thenComparing(SourceCheck::label));
        return checks;
    }

    private List<String> requiredAdapterKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (MigrationAdapter adapter : adapterRegistry.adapters()) {
            if (!adapter.isEnabled()) {
                continue;
            }
            boolean required = adapter.expectedSources().stream().anyMatch(PathExpectation::required);
            if (required) {
                keys.add(adapter.key());
            }
        }
        return List.copyOf(keys);
    }

    private Set<java.util.UUID> ignoredConflictUuids() {
        return Set.of(
                configService.config().residenceHolderUuid(),
                configService.config().quickshopHolderUuid()
        );
    }

    private PrepareStatus loadPrepareStatus(String adapterKey, String scanId) throws Exception {
        int scannedAssetCount = indexDatabase.countAdapterAssets(scanId, adapterKey);
        String prefix = "prepare." + adapterKey + ".";
        String preparedScanId = indexDatabase.readMetadataValue(prefix + "last_scan_id").orElse("");
        String preparedAt = indexDatabase.readMetadataValue(prefix + "last_prepared_at").orElse("");
        String prepareId = indexDatabase.readMetadataValue(prefix + "last_prepare_id").orElse("");
        int changedCount = parseInt(indexDatabase.readMetadataValue(prefix + "last_changed_count").orElse("0"));
        int targetCount = parseInt(indexDatabase.readMetadataValue(prefix + "last_target_count").orElse("0"));
        boolean current = scanId.equals(preparedScanId);
        return new PrepareStatus(adapterKey, scannedAssetCount, current ? changedCount : 0, current ? targetCount : 0, current, prepareId, preparedAt, preparedScanId);
    }

    private int parseInt(String rawValue) {
        try {
            return Integer.parseInt(rawValue);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String buildSummaryMarkdown(
            ScanSummary summary,
            List<NameConflict> conflicts,
            List<SourceCheck> sourceChecks,
            Optional<ArchiveManifestUtil.ArchiveManifest> archiveManifest,
            int floodgateSkippedCount,
            List<IndexDatabase.AccountSummaryRow> missingPrimaryNameAccounts,
            List<IndexDatabase.AccountSummaryRow> missingCoreAssetAccounts,
            PrepareStatus residencePrepareStatus,
            PrepareStatus quickshopPrepareStatus
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# UUIDMigrate Scan Report").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- Scan ID: `").append(summary.scanId()).append("`").append(System.lineSeparator());
        builder.append("- Snapshot ID: `").append(summary.snapshotId()).append("`").append(System.lineSeparator());
        builder.append("- Total legacy accounts: ").append(summary.totalAccounts()).append(System.lineSeparator());
        builder.append("- Accounts with primary name: ").append(summary.accountsWithPrimaryName()).append(System.lineSeparator());
        builder.append("- Claimable accounts: ").append(summary.claimableAccounts()).append(System.lineSeparator());
        builder.append("- Floodgate skipped: ").append(floodgateSkippedCount).append(System.lineSeparator());
        builder.append("- Conflict names: ").append(summary.conflictNameCount()).append(System.lineSeparator());
        builder.append("- Accounts in conflicts: ").append(summary.accountsInConflict()).append(System.lineSeparator());
        builder.append("- Asset rows: ").append(summary.totalAssets()).append(System.lineSeparator());
        builder.append("- Missing primary-name accounts: ").append(missingPrimaryNameAccounts.size()).append(System.lineSeparator());
        builder.append("- Missing core-asset accounts: ").append(missingCoreAssetAccounts.size()).append(System.lineSeparator());
        builder.append("- Residence prepared count: ").append(residencePrepareStatus.changedCount()).append(System.lineSeparator());
        builder.append("- QuickShop prepared count: ").append(quickshopPrepareStatus.changedCount()).append(System.lineSeparator());
        builder.append("- Archive manifest exists: ").append(archiveManifest.isPresent()).append(System.lineSeparator());
        if (archiveManifest.isPresent()) {
            builder.append("- Archive manifest path: `").append(archiveManifest.get().path()).append("`").append(System.lineSeparator());
            builder.append("- Archive manifest time: `").append(archiveManifest.get().modifiedAt()).append("`").append(System.lineSeparator());
            builder.append("- Archive manifest status: `").append(archiveManifest.get().status()).append("`").append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        builder.append("## Prepare Status").append(System.lineSeparator()).append(System.lineSeparator());
        appendPrepareStatus(builder, residencePrepareStatus, "Residence");
        appendPrepareStatus(builder, quickshopPrepareStatus, "QuickShop-Hikari");

        builder.append("## Source Checks").append(System.lineSeparator()).append(System.lineSeparator());
        for (SourceCheck check : sourceChecks) {
            builder.append("- [")
                    .append(check.exists() ? "x" : " ")
                    .append("] ")
                    .append(check.adapterKey())
                    .append(" / ")
                    .append(check.label())
                    .append(" / `")
                    .append(check.relativePath())
                    .append('`');
            if (check.required()) {
                builder.append(" (required)");
            }
            builder.append(System.lineSeparator());
        }

        builder.append(System.lineSeparator()).append("## Name Conflicts").append(System.lineSeparator()).append(System.lineSeparator());
        if (conflicts.isEmpty()) {
            builder.append("- none").append(System.lineSeparator());
        } else {
            for (NameConflict conflict : conflicts) {
                builder.append("- `")
                        .append(conflict.legacyName())
                        .append("` -> ")
                        .append(conflict.legacyUuids())
                        .append(System.lineSeparator());
            }
        }

        builder.append(System.lineSeparator()).append("## Missing Primary Name").append(System.lineSeparator()).append(System.lineSeparator());
        appendAccountList(builder, missingPrimaryNameAccounts);

        builder.append(System.lineSeparator()).append("## Missing Core Assets").append(System.lineSeparator()).append(System.lineSeparator());
        appendAccountList(builder, missingCoreAssetAccounts);

        builder.append(System.lineSeparator()).append("## Notes").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- Active migration support currently covers Vanilla, Essentials, XConomy, LuckPerms, Residence, QuickShop-Hikari, XyKit, SimplePlaytime, LiteSignIn, PlayerTitle, PlayerTask, HoloMobHealth, and fakeplayer.").append(System.lineSeparator());
        builder.append("- Residence and QuickShop-Hikari require running the prepare commands before claim mode.").append(System.lineSeparator());
        builder.append("- Claimable account count respects the current `skip-floodgate-players` and `allow-repeat-claim` configuration.").append(System.lineSeparator());
        return builder.toString();
    }

    private void appendPrepareStatus(StringBuilder builder, PrepareStatus status, String displayName) {
        builder.append("- ").append(displayName)
                .append(": scanned assets=")
                .append(status.scannedAssetCount())
                .append(", prepared owners=")
                .append(status.changedCount())
                .append(", touched targets=")
                .append(status.touchedTargetCount())
                .append(", current-scan=")
                .append(status.currentScan());
        if (!status.prepareId().isBlank()) {
            builder.append(", prepare-id=`").append(status.prepareId()).append('`');
        }
        if (!status.preparedAt().isBlank()) {
            builder.append(", prepared-at=`").append(status.preparedAt()).append('`');
        }
        if (!status.preparedScanId().isBlank() && !status.currentScan()) {
            builder.append(", prepared-scan=`").append(status.preparedScanId()).append('`');
        }
        builder.append(System.lineSeparator());
    }

    private void appendAccountList(StringBuilder builder, List<IndexDatabase.AccountSummaryRow> accounts) {
        if (accounts.isEmpty()) {
            builder.append("- none").append(System.lineSeparator());
            return;
        }
        for (IndexDatabase.AccountSummaryRow account : accounts) {
            String name = account.primaryName() == null || account.primaryName().isBlank() ? "<missing>" : account.primaryName();
            builder.append("- `").append(account.legacyUuid()).append("` / ").append(name).append(System.lineSeparator());
        }
    }

    private String buildConflictCsv(List<NameConflict> conflicts) {
        StringBuilder builder = new StringBuilder("legacy_name,legacy_uuid").append(System.lineSeparator());
        for (NameConflict conflict : conflicts) {
            for (var uuid : conflict.legacyUuids()) {
                builder.append(csv(conflict.legacyName()))
                        .append(',')
                        .append(csv(uuid.toString()))
                        .append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private String buildUnclaimedAssetCsv(List<IndexDatabase.AssetRow> assets) {
        StringBuilder builder = new StringBuilder("legacy_uuid,primary_name,adapter,asset_key").append(System.lineSeparator());
        for (IndexDatabase.AssetRow asset : assets) {
            builder.append(csv(asset.legacyUuid().toString())).append(',')
                    .append(csv(asset.primaryName())).append(',')
                    .append(csv(asset.adapter())).append(',')
                    .append(csv(asset.assetKey())).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String csv(String value) {
        String sanitized = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + sanitized + "\"";
    }

    public record SourceCheck(String adapterKey, String label, String relativePath, boolean required, boolean exists) {
    }

    public record ReportResult(
            ScanSummary summary,
            Path summaryPath,
            Path conflictPath,
            Path assetPath,
            List<SourceCheck> sourceChecks,
            boolean archiveManifestExists
    ) {
    }

    private record PrepareStatus(
            String adapterKey,
            int scannedAssetCount,
            int changedCount,
            int touchedTargetCount,
            boolean currentScan,
            String prepareId,
            String preparedAt,
            String preparedScanId
    ) {
    }
}
