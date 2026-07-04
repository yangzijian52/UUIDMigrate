package cn.uuidmigrate.service;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.adapter.AdapterRegistry;
import cn.uuidmigrate.adapter.PrepareAwareAdapter;
import cn.uuidmigrate.adapter.PrepareContext;
import cn.uuidmigrate.adapter.PrepareResult;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.util.ArchiveManifestUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class PrepareService {
    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final IndexDatabase indexDatabase;
    private final AdapterRegistry adapterRegistry;

    public PrepareService(
            UUIDMigratePlugin plugin,
            ConfigService configService,
            IndexDatabase indexDatabase,
            AdapterRegistry adapterRegistry
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.indexDatabase = indexDatabase;
        this.adapterRegistry = adapterRegistry;
    }

    public PrepareResult runPrepare(String adapterKey) throws Exception {
        if (configService.config().dryRun()) {
            throw new IllegalStateException("当前启用了 dry-run，不能执行预处理写入。");
        }

        ArchiveManifestUtil.validateForSnapshot(configService.config());
        String latestSnapshotId = indexDatabase.latestSnapshotId()
                .orElseThrow(() -> new IllegalStateException("当前没有扫描结果，请先运行 /uuidmigrate admin scan。"));
        if (!latestSnapshotId.equals(configService.config().snapshotId())) {
            throw new IllegalStateException("最近一次扫描的快照是 " + latestSnapshotId + "，但当前配置中的快照是 " + configService.config().snapshotId() + "，请先运行 /uuidmigrate admin scan。");
        }

        String scanId = indexDatabase.latestScanId()
                .orElseThrow(() -> new IllegalStateException("当前没有扫描结果，请先运行 /uuidmigrate admin scan。"));

        var adapter = adapterRegistry.findAdapter(adapterKey)
                .orElseThrow(() -> new IllegalArgumentException("未知的预处理目标: " + adapterKey));
        if (!(adapter instanceof PrepareAwareAdapter prepareAwareAdapter)) {
            throw new IllegalStateException("该适配器不支持预处理: " + adapterKey);
        }

        String prepareId = "prepare-" + adapterKey + "-" + Instant.now().toEpochMilli();
        Path backupRoot = configService.config().backupsDirectory().resolve(prepareId);
        Files.createDirectories(backupRoot);

        PrepareContext context = new PrepareContext(
                plugin,
                configService.config(),
                indexDatabase,
                adapterKey,
                scanId,
                prepareId,
                backupRoot,
                new ConcurrentHashMap<>()
        );

        try {
            PrepareResult result = prepareAwareAdapter.prepare(context);
            String prefix = "prepare." + adapterKey + ".";
            indexDatabase.writeMetadata(prefix + "last_prepare_id", prepareId);
            indexDatabase.writeMetadata(prefix + "last_scan_id", scanId);
            indexDatabase.writeMetadata(prefix + "last_prepared_at", Instant.now().toString());
            indexDatabase.writeMetadata(prefix + "last_asset_count", Integer.toString(result.assetCount()));
            indexDatabase.writeMetadata(prefix + "last_changed_count", Integer.toString(result.changedCount()));
            indexDatabase.writeMetadata(prefix + "last_target_count", Integer.toString(result.touchedTargetCount()));
            return result;
        } catch (Exception exception) {
            try {
                prepareAwareAdapter.rollbackPrepare(context);
            } catch (Exception rollbackException) {
                plugin.getLogger().severe(adapterKey + " 预处理回滚失败: " + rollbackException.getMessage());
            }
            throw exception;
        }
    }
}
