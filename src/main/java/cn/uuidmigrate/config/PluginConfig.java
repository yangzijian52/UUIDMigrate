package cn.uuidmigrate.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PluginConfig(
        PluginMode mode,
        Path serverRoot,
        Path dataDirectory,
        Path logsDirectory,
        Path backupsDirectory,
        Path reportsDirectory,
        Path legacyRoot,
        String snapshotId,
        int temporaryBanSeconds,
        String kickMessage,
        String banReason,
        boolean skipFloodgatePlayers,
        List<String> floodgatePrefixes,
        boolean allowOfflineUuidFallback,
        boolean allowRepeatClaim,
        boolean dryRun,
        boolean logDetail,
        UUID residenceHolderUuid,
        String residenceHolderName,
        UUID quickshopHolderUuid,
        String quickshopHolderName,
        boolean archiveToolExpectedMarkerEnabled,
        String archiveManifestPrefix,
        Path authMeSqlitePath,
        String authMeTable,
        String authMeUsernameColumn,
        String authMePasswordColumn,
        int pendingClaimTimeoutSeconds,
        Map<String, Boolean> adapterFlags
) {
    public Path snapshotRoot() {
        return legacyRoot.resolve(snapshotId).normalize();
    }

    public boolean isAdapterEnabled(String key) {
        return adapterFlags.getOrDefault(key, false);
    }
}
