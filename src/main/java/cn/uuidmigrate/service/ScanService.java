package cn.uuidmigrate.service;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.adapter.AdapterRegistry;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.model.ScanSummary;
import cn.uuidmigrate.util.ArchiveManifestUtil;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Set;

public final class ScanService {
    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final IndexDatabase indexDatabase;
    private final AdapterRegistry adapterRegistry;

    public ScanService(UUIDMigratePlugin plugin, ConfigService configService, IndexDatabase indexDatabase, AdapterRegistry adapterRegistry) {
        this.plugin = plugin;
        this.configService = configService;
        this.indexDatabase = indexDatabase;
        this.adapterRegistry = adapterRegistry;
    }

    public ScanSummary runFullScan() throws Exception {
        ArchiveManifestUtil.validateForSnapshot(configService.config());
        if (!Files.isDirectory(configService.config().snapshotRoot())) {
            throw new IllegalStateException("快照目录不存在: " + configService.config().snapshotRoot());
        }

        String scanId = Instant.now().toString();
        ScanContext context = new ScanContext(plugin, configService.config(), scanId);

        for (MigrationAdapter adapter : adapterRegistry.adapters()) {
            if (!adapter.isEnabled()) {
                continue;
            }
            adapter.scan(context);
        }

        indexDatabase.persistScan(scanId, configService.config().snapshotId(), context);
        return indexDatabase.loadSummary(
                scanId,
                configService.config().skipFloodgatePlayers(),
                configService.config().allowRepeatClaim(),
                ignoredConflictUuids()
        );
    }

    private Set<java.util.UUID> ignoredConflictUuids() {
        return Set.of(
                configService.config().residenceHolderUuid(),
                configService.config().quickshopHolderUuid()
        );
    }
}
