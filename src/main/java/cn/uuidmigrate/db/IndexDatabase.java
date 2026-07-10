package cn.uuidmigrate.db;

import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.model.ClaimIndexState;
import cn.uuidmigrate.model.ClaimStatus;
import cn.uuidmigrate.model.NameConflict;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.model.ScanSummary;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class IndexDatabase implements AutoCloseable {
    private final Path databasePath;
    private final String jdbcUrl;

    public IndexDatabase(JavaPlugin plugin) {
        this.databasePath = plugin.getDataFolder().toPath().resolve("index.db").toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + databasePath;
    }

    public void initialize() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS scan_run (
                      scan_id TEXT PRIMARY KEY,
                      snapshot_id TEXT NOT NULL,
                      started_at TEXT NOT NULL,
                      finished_at TEXT,
                      status TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS legacy_account (
                      legacy_uuid TEXT PRIMARY KEY,
                      platform_type TEXT NOT NULL,
                      primary_name TEXT,
                      claim_status TEXT NOT NULL DEFAULT 'UNCLAIMED',
                      claimed_by_uuid TEXT,
                      claimed_by_name TEXT,
                      claimed_at TEXT,
                      notes TEXT,
                      last_scan_id TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS legacy_name (
                      scan_id TEXT NOT NULL,
                      legacy_uuid TEXT NOT NULL,
                      name TEXT NOT NULL,
                      source TEXT NOT NULL,
                      is_primary INTEGER NOT NULL DEFAULT 0,
                      UNIQUE(scan_id, legacy_uuid, name, source)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS legacy_asset (
                      scan_id TEXT NOT NULL,
                      legacy_uuid TEXT NOT NULL,
                      adapter TEXT NOT NULL,
                      asset_key TEXT NOT NULL,
                      asset_meta_json TEXT,
                      UNIQUE(scan_id, legacy_uuid, adapter, asset_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS claim_log (
                      claim_id TEXT PRIMARY KEY,
                      legacy_uuid TEXT NOT NULL,
                      scan_id TEXT,
                      new_uuid TEXT NOT NULL,
                      new_name TEXT NOT NULL,
                      started_at TEXT NOT NULL,
                      finished_at TEXT,
                      status TEXT NOT NULL,
                      error_message TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS name_resolution (
                      legacy_name TEXT NOT NULL,
                      chosen_legacy_uuid TEXT NOT NULL,
                      resolved_by TEXT NOT NULL,
                      resolved_at TEXT NOT NULL,
                      UNIQUE(legacy_name)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_metadata (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """);
            ensureColumnExists(connection, "claim_log", "scan_id", "TEXT");
        }
    }

    public void persistScan(String scanId, String snapshotId, ScanContext context) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                insertScanRun(connection, scanId, snapshotId);
                deleteScanRows(connection, scanId);
                for (ScanContext.AccountScanRecord record : context.accounts().values()) {
                    upsertAccount(connection, scanId, record.legacyUuid(), record.platformType());
                    for (ScanContext.NameScanRecord name : record.names()) {
                        insertName(connection, scanId, record.legacyUuid(), name);
                    }
                    for (ScanContext.AssetScanRecord asset : record.assets()) {
                        insertAsset(connection, scanId, record.legacyUuid(), asset);
                    }
                }
                promotePrimaryNames(connection, scanId);
                markScanCompleted(connection, scanId);
                upsertMetadata(connection, "latest_scan_id", scanId);
                upsertMetadata(connection, "latest_snapshot_id", snapshotId);
                upsertMetadata(connection, "latest_scan_finished_at", Instant.now().toString());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<String> latestScanId() throws SQLException {
        return readMetadata("latest_scan_id");
    }

    public Optional<String> latestSnapshotId() throws SQLException {
        return readMetadata("latest_snapshot_id");
    }

    public ScanSummary loadSummary(String scanId, boolean skipFloodgatePlayers, boolean allowRepeatClaim) throws SQLException {
        return loadSummary(scanId, skipFloodgatePlayers, allowRepeatClaim, Collections.emptySet());
    }

    public ScanSummary loadSummary(String scanId, boolean skipFloodgatePlayers, boolean allowRepeatClaim, Set<UUID> ignoredConflictUuids) throws SQLException {
        String snapshotId = latestSnapshotId().orElse("unknown");
        int totalAccounts = queryForInt("""
                SELECT COUNT(*) FROM legacy_account WHERE last_scan_id = ?
                """, scanId);
        int accountsWithPrimaryName = queryForInt("""
                SELECT COUNT(*)
                FROM legacy_account
                WHERE last_scan_id = ?
                  AND primary_name IS NOT NULL
                  AND TRIM(primary_name) <> ''
                """, scanId);
        int totalAssets = queryForInt("""
                SELECT COUNT(*) FROM legacy_asset WHERE scan_id = ?
                """, scanId);
        List<NameConflict> conflicts = loadNameConflicts(scanId, ignoredConflictUuids);
        Set<UUID> accountsInConflictSet = new LinkedHashSet<>();
        for (NameConflict conflict : conflicts) {
            accountsInConflictSet.addAll(conflict.legacyUuids());
        }
        int conflictNameCount = conflicts.size();
        int accountsInConflict = accountsInConflictSet.size();
        String claimableSql = """
                SELECT legacy_uuid
                FROM legacy_account
                WHERE last_scan_id = ?
                  AND primary_name IS NOT NULL
                  AND TRIM(primary_name) <> ''
                  AND claim_status <> 'LOCKED'
                  AND platform_type <> 'JAVA_ONLINE'
                """;
        if (!allowRepeatClaim) {
            claimableSql += """
                      AND claim_status <> 'CLAIMED'
                    """;
        }
        if (skipFloodgatePlayers) {
            claimableSql += """
                      AND platform_type <> 'FLOODGATE'
                    """;
        }
        List<UUID> claimableCandidates = queryForUuidList(claimableSql, scanId);
        int claimableAccounts = (int) claimableCandidates.stream()
                .filter(uuid -> !accountsInConflictSet.contains(uuid))
                .count();

        return new ScanSummary(
                scanId,
                snapshotId,
                totalAccounts,
                accountsWithPrimaryName,
                claimableAccounts,
                conflictNameCount,
                accountsInConflict,
                totalAssets
        );
    }

    public List<NameConflict> loadNameConflicts(String scanId) throws SQLException {
        return loadNameConflicts(scanId, Collections.emptySet());
    }

    public List<NameConflict> loadNameConflicts(String scanId, Set<UUID> ignoredConflictUuids) throws SQLException {
        List<NameConflict> conflicts = new ArrayList<>();
        String sql = """
                SELECT DISTINCT
                       n.name,
                       acc.legacy_uuid,
                       acc.platform_type,
                       COALESCE(acc.primary_name, '') AS primary_name,
                       acc.claim_status,
                       (
                         SELECT COUNT(*)
                         FROM legacy_asset asset
                         WHERE asset.scan_id = n.scan_id
                           AND asset.legacy_uuid = acc.legacy_uuid
                       ) AS asset_count
                FROM legacy_name n
                JOIN legacy_account acc ON acc.legacy_uuid = n.legacy_uuid
                WHERE n.scan_id = ?
                ORDER BY LOWER(n.name), n.name, acc.legacy_uuid
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scanId);

            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, NameCandidateAccumulator> grouped = new LinkedHashMap<>();
                while (resultSet.next()) {
                    String name = resultSet.getString("name");
                    UUID uuid = UUID.fromString(resultSet.getString("legacy_uuid"));
                    if (ignoredConflictUuids.contains(uuid) || shouldIgnoreConflictName(name)) {
                        continue;
                    }

                    String normalized = name.toLowerCase(Locale.ROOT);
                    NameCandidateAccumulator accumulator = grouped.computeIfAbsent(normalized, unused -> new NameCandidateAccumulator(name));
                    accumulator.candidates().add(new NameCandidate(
                            name,
                            uuid,
                            resultSet.getString("primary_name"),
                            ClaimStatus.valueOf(resultSet.getString("claim_status")),
                            PlatformType.valueOf(resultSet.getString("platform_type")),
                            resultSet.getInt("asset_count")
                    ));
                }

                for (NameCandidateAccumulator accumulator : grouped.values()) {
                    List<NameCandidate> filtered = applyNameConflictHeuristics(accumulator.displayName(), accumulator.candidates());
                    if (filtered.size() > 1) {
                        conflicts.add(new NameConflict(
                                accumulator.displayName(),
                                filtered.stream().map(NameCandidate::legacyUuid).toList()
                        ));
                    }
                }
            }
        }

        return conflicts;
    }

    public List<AssetRow> loadUnclaimedAssets(String scanId) throws SQLException {
        List<AssetRow> assets = new ArrayList<>();
        String sql = """
                SELECT a.legacy_uuid, a.adapter, a.asset_key, COALESCE(acc.primary_name, '') AS primary_name
                FROM legacy_asset a
                JOIN legacy_account acc ON acc.legacy_uuid = a.legacy_uuid
                WHERE a.scan_id = ?
                  AND acc.claim_status <> ?
                ORDER BY a.adapter, a.legacy_uuid, a.asset_key
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scanId);
            statement.setString(2, ClaimStatus.CLAIMED.name());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    assets.add(new AssetRow(
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("primary_name"),
                            resultSet.getString("adapter"),
                            resultSet.getString("asset_key")
                    ));
                }
            }
        }

        return assets;
    }

    public List<IndexedAssetRow> loadAdapterAssets(String scanId, String adapterKey) throws SQLException {
        List<IndexedAssetRow> assets = new ArrayList<>();
        String sql = """
                SELECT legacy_uuid, asset_key, asset_meta_json
                FROM legacy_asset
                WHERE scan_id = ?
                  AND LOWER(adapter) = LOWER(?)
                ORDER BY legacy_uuid, asset_key
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scanId);
            statement.setString(2, adapterKey);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    assets.add(new IndexedAssetRow(
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("asset_key"),
                            resultSet.getString("asset_meta_json")
                    ));
                }
            }
        }

        return assets;
    }

    public List<IndexedAssetRow> loadAdapterAssets(String scanId, String adapterKey, UUID legacyUuid) throws SQLException {
        List<IndexedAssetRow> assets = new ArrayList<>();
        String sql = """
                SELECT legacy_uuid, asset_key, asset_meta_json
                FROM legacy_asset
                WHERE scan_id = ?
                  AND LOWER(adapter) = LOWER(?)
                  AND legacy_uuid = ?
                ORDER BY asset_key
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scanId);
            statement.setString(2, adapterKey);
            statement.setString(3, legacyUuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    assets.add(new IndexedAssetRow(
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("asset_key"),
                            resultSet.getString("asset_meta_json")
                    ));
                }
            }
        }

        return assets;
    }

    public List<NameMatchRow> findNameMatches(String scanId, String legacyName) throws SQLException {
        return findNameMatches(scanId, legacyName, Collections.emptySet());
    }

    public Optional<ClaimPromptCandidateRow> findClaimPromptCandidate(
            String scanId,
            String legacyName,
            Set<UUID> ignoredLegacyUuids,
            boolean skipFloodgatePlayers
    ) throws SQLException {
        List<NameMatchRow> matches = findNameMatches(scanId, legacyName, ignoredLegacyUuids);
        if (matches.size() != 1) {
            return Optional.empty();
        }

        NameMatchRow match = matches.get(0);
        if (match.claimStatus() == ClaimStatus.CLAIMED || match.claimStatus() == ClaimStatus.LOCKED) {
            return Optional.empty();
        }

        Optional<LegacyAccountRow> account = findLegacyAccount(match.legacyUuid());
        if (account.isEmpty()) {
            return Optional.empty();
        }
        if (!scanId.equals(account.get().lastScanId())) {
            return Optional.empty();
        }
        if (account.get().platformType() == PlatformType.JAVA_ONLINE) {
            return Optional.empty();
        }
        if (account.get().platformType() == PlatformType.FLOODGATE && skipFloodgatePlayers) {
            return Optional.empty();
        }

        return Optional.of(new ClaimPromptCandidateRow(
                match.legacyUuid(),
                legacyName,
                match.primaryName(),
                match.claimStatus()
        ));
    }

    public List<NameMatchRow> findNameMatches(String scanId, String legacyName, Set<UUID> ignoredLegacyUuids) throws SQLException {
        List<NameCandidate> candidates = new ArrayList<>();
        String sql = """
                SELECT DISTINCT
                       acc.legacy_uuid,
                       COALESCE(acc.primary_name, '') AS primary_name,
                       acc.claim_status,
                       acc.platform_type,
                       (
                         SELECT COUNT(*)
                         FROM legacy_asset asset
                         WHERE asset.scan_id = n.scan_id
                           AND asset.legacy_uuid = acc.legacy_uuid
                       ) AS asset_count
                FROM legacy_name n
                JOIN legacy_account acc ON acc.legacy_uuid = n.legacy_uuid
                WHERE n.scan_id = ?
                  AND LOWER(n.name) = LOWER(?)
                ORDER BY acc.legacy_uuid
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scanId);
            statement.setString(2, legacyName);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID legacyUuid = UUID.fromString(resultSet.getString("legacy_uuid"));
                    if (ignoredLegacyUuids.contains(legacyUuid)) {
                        continue;
                    }
                    candidates.add(new NameCandidate(
                            legacyName,
                            legacyUuid,
                            resultSet.getString("primary_name"),
                            ClaimStatus.valueOf(resultSet.getString("claim_status")),
                            PlatformType.valueOf(resultSet.getString("platform_type")),
                            resultSet.getInt("asset_count")
                    ));
                }
            }
        }

        return applyNameConflictHeuristics(legacyName, candidates).stream()
                .map(candidate -> new NameMatchRow(candidate.legacyUuid(), candidate.primaryName(), candidate.claimStatus()))
                .toList();
    }

    public Optional<LegacyAccountRow> findLegacyAccount(UUID legacyUuid) throws SQLException {
        String sql = """
                SELECT legacy_uuid, platform_type, COALESCE(primary_name, '') AS primary_name, claim_status, claimed_by_uuid, claimed_by_name, claimed_at, last_scan_id
                FROM legacy_account
                WHERE legacy_uuid = ?
                LIMIT 1
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, legacyUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new LegacyAccountRow(
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            PlatformType.valueOf(resultSet.getString("platform_type")),
                            resultSet.getString("primary_name"),
                            ClaimStatus.valueOf(resultSet.getString("claim_status")),
                            resultSet.getString("claimed_by_uuid"),
                            resultSet.getString("claimed_by_name"),
                            resultSet.getString("claimed_at"),
                            resultSet.getString("last_scan_id")
                    ));
                }
            }
        }

        return Optional.empty();
    }

    public Optional<PlayerClaimRow> findClaimedByNewUuid(UUID newUuid) throws SQLException {
        String sql = """
                SELECT legacy_uuid, COALESCE(primary_name, '') AS primary_name, claim_status, claimed_at
                FROM legacy_account
                WHERE claimed_by_uuid = ?
                LIMIT 1
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newUuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new PlayerClaimRow(
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("primary_name"),
                            ClaimStatus.valueOf(resultSet.getString("claim_status")),
                            resultSet.getString("claimed_at")
                    ));
                }
            }
        }

        return Optional.empty();
    }

    public Map<UUID, ClaimedUuidBindingRow> loadClaimedUuidBindings() throws SQLException {
        Map<UUID, ClaimedUuidBindingRow> rows = new LinkedHashMap<>();
        String sql = """
                SELECT legacy_uuid, claimed_by_uuid, COALESCE(claimed_by_name, '') AS claimed_by_name
                FROM legacy_account
                WHERE claim_status = ?
                  AND claimed_by_uuid IS NOT NULL
                  AND TRIM(claimed_by_uuid) <> ''
                ORDER BY claimed_at, legacy_uuid
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ClaimStatus.CLAIMED.name());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID legacyUuid = UUID.fromString(resultSet.getString("legacy_uuid"));
                    rows.put(legacyUuid, new ClaimedUuidBindingRow(
                            legacyUuid,
                            UUID.fromString(resultSet.getString("claimed_by_uuid")),
                            resultSet.getString("claimed_by_name")
                    ));
                }
            }
        }

        return rows;
    }

    public Optional<UUID> findResolvedLegacyUuid(String legacyName) throws SQLException {
        String sql = """
                SELECT chosen_legacy_uuid
                FROM name_resolution
                WHERE LOWER(legacy_name) = LOWER(?)
                LIMIT 1
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, legacyName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(UUID.fromString(resultSet.getString("chosen_legacy_uuid")));
                }
            }
        }

        return Optional.empty();
    }

    public void resolveNameConflict(String legacyName, UUID chosenLegacyUuid, String resolvedBy) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO name_resolution(legacy_name, chosen_legacy_uuid, resolved_by, resolved_at)
                     VALUES (?, ?, ?, ?)
                     ON CONFLICT(legacy_name) DO UPDATE SET
                       chosen_legacy_uuid = excluded.chosen_legacy_uuid,
                       resolved_by = excluded.resolved_by,
                       resolved_at = excluded.resolved_at
                     """)) {
            statement.setString(1, legacyName);
            statement.setString(2, chosenLegacyUuid.toString());
            statement.setString(3, resolvedBy);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public UnlockResult unlockLegacyAccount(UUID legacyUuid) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ClaimStatus currentStatus;
                String claimedByUuid;
                String claimedByName;
                String claimedAt;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT claim_status, claimed_by_uuid, claimed_by_name, claimed_at
                        FROM legacy_account
                        WHERE legacy_uuid = ?
                        LIMIT 1
                        """)) {
                    statement.setString(1, legacyUuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("Legacy UUID not found in index: " + legacyUuid);
                        }
                        currentStatus = ClaimStatus.valueOf(resultSet.getString("claim_status"));
                        claimedByUuid = resultSet.getString("claimed_by_uuid");
                        claimedByName = resultSet.getString("claimed_by_name");
                        claimedAt = resultSet.getString("claimed_at");
                    }
                }

                if (currentStatus == ClaimStatus.CLAIMED) {
                    throw new IllegalStateException("This legacy account is already claimed. Use rollback instead of unlock.");
                }
                if (currentStatus != ClaimStatus.LOCKED) {
                    throw new IllegalStateException("Only LOCKED legacy accounts can be unlocked. Current status: " + currentStatus);
                }

                int runningClaimCount = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM claim_log
                        WHERE legacy_uuid = ?
                          AND status IN (?, ?)
                        """)) {
                    statement.setString(1, legacyUuid.toString());
                    statement.setString(2, "RUNNING");
                    statement.setString(3, "MANUAL_INTERVENTION");
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            runningClaimCount = resultSet.getInt(1);
                        }
                    }
                }
                if (runningClaimCount > 0) {
                    throw new IllegalStateException("This legacy account still has an interrupted claim. Use rollback instead of unlock.");
                }

                ClaimStatus restoredStatus = hasText(claimedByUuid) ? ClaimStatus.CLAIMED : ClaimStatus.UNCLAIMED;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE legacy_account
                        SET claim_status = ?,
                            claimed_by_uuid = ?,
                            claimed_by_name = ?,
                            claimed_at = ?
                        WHERE legacy_uuid = ?
                        """)) {
                    statement.setString(1, restoredStatus.name());
                    statement.setString(2, restoredStatus == ClaimStatus.CLAIMED ? claimedByUuid : null);
                    statement.setString(3, restoredStatus == ClaimStatus.CLAIMED ? claimedByName : null);
                    statement.setString(4, restoredStatus == ClaimStatus.CLAIMED ? claimedAt : null);
                    statement.setString(5, legacyUuid.toString());
                    statement.executeUpdate();
                }

                connection.commit();
                return new UnlockResult(restoredStatus);
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException(exception.getMessage(), exception);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<ClaimLogRow> findLatestClaimByNewUuid(UUID newUuid) throws SQLException {
        String sql = """
                SELECT claim_id, legacy_uuid, scan_id, new_name, started_at, finished_at, status, error_message
                FROM claim_log
                WHERE new_uuid = ?
                ORDER BY started_at DESC
                LIMIT 1
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newUuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new ClaimLogRow(
                            resultSet.getString("claim_id"),
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("scan_id"),
                            resultSet.getString("new_name"),
                            resultSet.getString("started_at"),
                            resultSet.getString("finished_at"),
                            resultSet.getString("status"),
                            resultSet.getString("error_message")
                    ));
                }
            }
        }

        return Optional.empty();
    }

    public boolean hasRunningClaimForNewUuid(UUID newUuid) throws SQLException {
        return queryForInt("""
                SELECT COUNT(*)
                FROM claim_log
                WHERE new_uuid = ?
                  AND status IN (?, ?)
                """, newUuid.toString(), "RUNNING", "MANUAL_INTERVENTION") > 0;
    }

    public List<ClaimDetailRow> findClaimsByStatus(String status) throws SQLException {
        List<ClaimDetailRow> rows = new ArrayList<>();
        String sql = """
                SELECT claim_id, legacy_uuid, scan_id, new_uuid, new_name, started_at, finished_at, status, error_message
                FROM claim_log
                WHERE status = ?
                ORDER BY started_at
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new ClaimDetailRow(
                            resultSet.getString("claim_id"),
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("scan_id"),
                            UUID.fromString(resultSet.getString("new_uuid")),
                            resultSet.getString("new_name"),
                            resultSet.getString("started_at"),
                            resultSet.getString("finished_at"),
                            resultSet.getString("status"),
                            resultSet.getString("error_message")
                    ));
                }
            }
        }

        return rows;
    }

    public Optional<ClaimDetailRow> findClaimById(String claimId) throws SQLException {
        String sql = """
                SELECT claim_id, legacy_uuid, scan_id, new_uuid, new_name, started_at, finished_at, status, error_message
                FROM claim_log
                WHERE claim_id = ?
                LIMIT 1
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, claimId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new ClaimDetailRow(
                            resultSet.getString("claim_id"),
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("scan_id"),
                            UUID.fromString(resultSet.getString("new_uuid")),
                            resultSet.getString("new_name"),
                            resultSet.getString("started_at"),
                            resultSet.getString("finished_at"),
                            resultSet.getString("status"),
                            resultSet.getString("error_message")
                    ));
                }
            }
        }

        return Optional.empty();
    }

    public Optional<String> findPreferredLegacyName(String scanId, UUID legacyUuid) throws SQLException {
        String sql = """
                SELECT name
                FROM legacy_name
                WHERE scan_id = ?
                  AND legacy_uuid = ?
                ORDER BY is_primary DESC,
                         CASE
                           WHEN LOWER(source) = 'essentials_last_account_name' THEN 0
                           WHEN LOWER(source) = 'luckperms_username' THEN 1
                           WHEN LOWER(source) = 'xconomy_player' THEN 2
                           WHEN LOWER(source) = 'litesignin_name' THEN 3
                           ELSE 50
                         END,
                         LOWER(name),
                         name
                LIMIT 1
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scanId);
            statement.setString(2, legacyUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.ofNullable(resultSet.getString("name"))
                            .map(String::trim)
                            .filter(name -> !name.isEmpty());
                }
            }
        }

        return Optional.empty();
    }

    public int countAccountsByPlatform(String scanId, PlatformType platformType) throws SQLException {
        return queryForInt("""
                SELECT COUNT(*)
                FROM legacy_account
                WHERE last_scan_id = ?
                  AND platform_type = ?
                """, scanId, platformType.name());
    }

    public List<AccountSummaryRow> loadAccountsMissingPrimaryName(String scanId) throws SQLException {
        List<AccountSummaryRow> rows = new ArrayList<>();
        String sql = """
                SELECT legacy_uuid, COALESCE(primary_name, '') AS primary_name
                FROM legacy_account
                WHERE last_scan_id = ?
                  AND (primary_name IS NULL OR TRIM(primary_name) = '')
                ORDER BY legacy_uuid
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scanId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new AccountSummaryRow(
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("primary_name")
                    ));
                }
            }
        }

        return rows;
    }

    public List<AccountSummaryRow> loadAccountsMissingAssetsForAdapters(String scanId, List<String> adapterKeys) throws SQLException {
        if (adapterKeys == null || adapterKeys.isEmpty()) {
            return List.of();
        }

        List<AccountSummaryRow> rows = new ArrayList<>();
        String placeholders = String.join(", ", Collections.nCopies(adapterKeys.size(), "?"));
        String sql = """
                SELECT acc.legacy_uuid, COALESCE(acc.primary_name, '') AS primary_name
                FROM legacy_account acc
                WHERE acc.last_scan_id = ?
                  AND NOT EXISTS (
                    SELECT 1
                    FROM legacy_asset asset
                    WHERE asset.scan_id = ?
                      AND asset.legacy_uuid = acc.legacy_uuid
                      AND LOWER(asset.adapter) IN (""" + placeholders + ")"
                + """
                  )
                ORDER BY acc.primary_name, acc.legacy_uuid
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, scanId);
            statement.setString(index++, scanId);
            for (String adapterKey : adapterKeys) {
                statement.setString(index++, adapterKey.toLowerCase());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new AccountSummaryRow(
                            UUID.fromString(resultSet.getString("legacy_uuid")),
                            resultSet.getString("primary_name")
                    ));
                }
            }
        }

        return rows;
    }

    public int countAdapterAssets(String scanId, String adapterKey) throws SQLException {
        return queryForInt("""
                SELECT COUNT(*)
                FROM legacy_asset
                WHERE scan_id = ?
                  AND LOWER(adapter) = LOWER(?)
                """, scanId, adapterKey);
    }

    public Optional<String> readMetadataValue(String key) throws SQLException {
        return readMetadata(key);
    }

    public String tryStartClaim(String claimId, UUID legacyUuid, UUID newUuid, String newName, String scanId, boolean allowRepeatClaim) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ClaimStatus currentStatus;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT claim_status
                        FROM legacy_account
                        WHERE legacy_uuid = ?
                        """)) {
                    statement.setString(1, legacyUuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("Legacy UUID not found in index: " + legacyUuid);
                        }
                        currentStatus = ClaimStatus.valueOf(resultSet.getString("claim_status"));
                    }
                }

                if (currentStatus == ClaimStatus.LOCKED) {
                    throw new IllegalStateException("This legacy account is already locked by another migration.");
                }
                if (currentStatus == ClaimStatus.CLAIMED && !allowRepeatClaim) {
                    throw new IllegalStateException("这个旧账号已经被认领过了。");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT legacy_uuid
                        FROM legacy_account
                        WHERE claimed_by_uuid = ?
                        LIMIT 1
                        """)) {
                    statement.setString(1, newUuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            throw new IllegalStateException("当前这个正版账号已经完成过一次认领，不能重复使用 /uuidmigrate claim。");
                        }
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE legacy_account
                        SET claim_status = ?
                        WHERE legacy_uuid = ?
                        """)) {
                    statement.setString(1, ClaimStatus.LOCKED.name());
                    statement.setString(2, legacyUuid.toString());
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO claim_log(claim_id, legacy_uuid, scan_id, new_uuid, new_name, started_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, claimId);
                    statement.setString(2, legacyUuid.toString());
                    statement.setString(3, scanId);
                    statement.setString(4, newUuid.toString());
                    statement.setString(5, newName);
                    statement.setString(6, Instant.now().toString());
                    statement.setString(7, "RUNNING");
                    statement.executeUpdate();
                }

                connection.commit();
                return claimId;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException(exception.getMessage(), exception);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void markClaimRolledBack(String claimId, UUID legacyUuid) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE legacy_account
                        SET claim_status = ?,
                            claimed_by_uuid = NULL,
                            claimed_by_name = NULL,
                            claimed_at = NULL
                        WHERE legacy_uuid = ?
                        """)) {
                    statement.setString(1, ClaimStatus.UNCLAIMED.name());
                    statement.setString(2, legacyUuid.toString());
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE claim_log
                        SET status = ?, finished_at = ?
                        WHERE claim_id = ?
                        """)) {
                    statement.setString(1, "ROLLED_BACK");
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, claimId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void restoreClaimState(
            String claimId,
            UUID legacyUuid,
            ClaimStatus claimStatus,
            String claimedByUuid,
            String claimedByName,
            String claimedAt
    ) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE legacy_account
                        SET claim_status = ?,
                            claimed_by_uuid = ?,
                            claimed_by_name = ?,
                            claimed_at = ?
                        WHERE legacy_uuid = ?
                        """)) {
                    statement.setString(1, claimStatus.name());
                    statement.setString(2, claimedByUuid);
                    statement.setString(3, claimedByName);
                    statement.setString(4, claimedAt);
                    statement.setString(5, legacyUuid.toString());
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE claim_log
                        SET status = ?, finished_at = ?
                        WHERE claim_id = ?
                        """)) {
                    statement.setString(1, "ROLLED_BACK");
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, claimId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void markClaimSucceeded(String claimId, UUID legacyUuid, UUID newUuid, String newName) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                String finishedAt = Instant.now().toString();

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE legacy_account
                        SET claim_status = ?,
                            claimed_by_uuid = ?,
                            claimed_by_name = ?,
                            claimed_at = ?
                        WHERE legacy_uuid = ?
                        """)) {
                    statement.setString(1, ClaimStatus.CLAIMED.name());
                    statement.setString(2, newUuid.toString());
                    statement.setString(3, newName);
                    statement.setString(4, finishedAt);
                    statement.setString(5, legacyUuid.toString());
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE claim_log
                        SET finished_at = ?, status = ?
                        WHERE claim_id = ?
                        """)) {
                    statement.setString(1, finishedAt);
                    statement.setString(2, "SUCCESS");
                    statement.setString(3, claimId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void markClaimFailed(String claimId, UUID legacyUuid, String errorMessage) throws SQLException {
        markClaimFailed(claimId, legacyUuid, errorMessage, null);
    }

    public void markClaimFailed(String claimId, UUID legacyUuid, String errorMessage, ClaimIndexState previousState) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                String finishedAt = Instant.now().toString();

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE legacy_account
                        SET claim_status = ?,
                            claimed_by_uuid = ?,
                            claimed_by_name = ?,
                            claimed_at = ?
                        WHERE legacy_uuid = ?
                        """)) {
                    ClaimIndexState failureState = effectiveFailureState(previousState);
                    statement.setString(1, failureState.claimStatus().name());
                    statement.setString(2, failureState.claimedByUuid());
                    statement.setString(3, failureState.claimedByName());
                    statement.setString(4, failureState.claimedAt());
                    statement.setString(5, legacyUuid.toString());
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE claim_log
                        SET finished_at = ?, status = ?, error_message = ?
                        WHERE claim_id = ?
                        """)) {
                    statement.setString(1, finishedAt);
                    statement.setString(2, "FAILED");
                    statement.setString(3, errorMessage);
                    statement.setString(4, claimId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void markClaimRequiresManualIntervention(String claimId, String errorMessage) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE claim_log
                     SET finished_at = COALESCE(finished_at, ?),
                         status = ?,
                         error_message = ?
                     WHERE claim_id = ?
                     """)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, "MANUAL_INTERVENTION");
            statement.setString(3, errorMessage);
            statement.setString(4, claimId);
            statement.executeUpdate();
        }
    }

    private ClaimIndexState effectiveFailureState(ClaimIndexState previousState) {
        if (previousState == null || previousState.claimStatus() == null) {
            return new ClaimIndexState(ClaimStatus.FAILED, null, null, null);
        }
        if (previousState.claimStatus() == ClaimStatus.UNCLAIMED || previousState.claimStatus() == ClaimStatus.LOCKED) {
            return new ClaimIndexState(ClaimStatus.FAILED, null, null, null);
        }
        return previousState;
    }

    private Optional<String> readMetadata(String key) throws SQLException {
        String sql = "SELECT value FROM plugin_metadata WHERE key = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.ofNullable(resultSet.getString("value")) : Optional.empty();
            }
        }
    }

    public void writeMetadata(String key, String value) throws SQLException {
        try (Connection connection = openConnection()) {
            upsertMetadata(connection, key, value);
        }
    }

    private void insertScanRun(Connection connection, String scanId, String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scan_run(scan_id, snapshot_id, started_at, status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(scan_id) DO UPDATE SET
                  snapshot_id = excluded.snapshot_id,
                  started_at = excluded.started_at,
                  finished_at = NULL,
                  status = excluded.status
                """)) {
            statement.setString(1, scanId);
            statement.setString(2, snapshotId);
            statement.setString(3, Instant.now().toString());
            statement.setString(4, "RUNNING");
            statement.executeUpdate();
        }
    }

    private void deleteScanRows(Connection connection, String scanId) throws SQLException {
        try (PreparedStatement deleteNames = connection.prepareStatement("DELETE FROM legacy_name WHERE scan_id = ?");
             PreparedStatement deleteAssets = connection.prepareStatement("DELETE FROM legacy_asset WHERE scan_id = ?")) {
            deleteNames.setString(1, scanId);
            deleteNames.executeUpdate();
            deleteAssets.setString(1, scanId);
            deleteAssets.executeUpdate();
        }
    }

    private void upsertAccount(Connection connection, String scanId, UUID legacyUuid, PlatformType platformType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO legacy_account(legacy_uuid, platform_type, claim_status, last_scan_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(legacy_uuid) DO UPDATE SET
                  platform_type = excluded.platform_type,
                  last_scan_id = excluded.last_scan_id
                """)) {
            statement.setString(1, legacyUuid.toString());
            statement.setString(2, platformType.name());
            statement.setString(3, ClaimStatus.UNCLAIMED.name());
            statement.setString(4, scanId);
            statement.executeUpdate();
        }
    }

    private void insertName(Connection connection, String scanId, UUID legacyUuid, ScanContext.NameScanRecord name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO legacy_name(scan_id, legacy_uuid, name, source, is_primary)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, scanId);
            statement.setString(2, legacyUuid.toString());
            statement.setString(3, name.name());
            statement.setString(4, name.source());
            statement.setInt(5, name.primary() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertAsset(Connection connection, String scanId, UUID legacyUuid, ScanContext.AssetScanRecord asset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO legacy_asset(scan_id, legacy_uuid, adapter, asset_key, asset_meta_json)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, scanId);
            statement.setString(2, legacyUuid.toString());
            statement.setString(3, asset.adapterKey());
            statement.setString(4, asset.assetKey());
            statement.setString(5, asset.assetMetaJson());
            statement.executeUpdate();
        }
    }

    private void promotePrimaryNames(Connection connection, String scanId) throws SQLException {
        try (PreparedStatement clearNames = connection.prepareStatement("""
                UPDATE legacy_account
                SET primary_name = NULL
                WHERE last_scan_id = ?
                """)) {
            clearNames.setString(1, scanId);
            clearNames.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE legacy_account
                SET primary_name = (
                    SELECT name
                    FROM legacy_name
                    WHERE legacy_name.legacy_uuid = legacy_account.legacy_uuid
                      AND legacy_name.scan_id = ?
                    ORDER BY is_primary DESC,
                             CASE
                               WHEN LOWER(source) = 'essentials_last_account_name' THEN 0
                               WHEN LOWER(source) = 'luckperms_username' THEN 1
                               WHEN LOWER(source) = 'xconomy_player' THEN 2
                               WHEN LOWER(source) = 'litesignin_name' THEN 3
                               ELSE 50
                             END,
                             LOWER(name),
                             name
                    LIMIT 1
                )
                WHERE last_scan_id = ?
                """)) {
            statement.setString(1, scanId);
            statement.setString(2, scanId);
            statement.executeUpdate();
        }
    }

    private void markScanCompleted(Connection connection, String scanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE scan_run
                SET finished_at = ?, status = ?
                WHERE scan_id = ?
                """)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, "SUCCESS");
            statement.setString(3, scanId);
            statement.executeUpdate();
        }
    }

    private void upsertMetadata(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plugin_metadata(key, value)
                VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private void ensureColumnExists(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        if (columnExists(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int queryForInt(String sql, String... params) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setString(index + 1, params[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private List<UUID> queryForUuidList(String sql, String... params) throws SQLException {
        List<UUID> results = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setString(index + 1, params[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(UUID.fromString(resultSet.getString(1)));
                }
            }
        }
        return results;
    }

    private boolean shouldIgnoreConflictName(String name) {
        if (!hasText(name)) {
            return true;
        }
        int underscore = name.lastIndexOf('_');
        if (underscore <= 0 || underscore == name.length() - 1) {
            return false;
        }
        for (int index = underscore + 1; index < name.length(); index++) {
            if (!Character.isDigit(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private List<NameCandidate> applyNameConflictHeuristics(String legacyName, List<NameCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<NameCandidate> filtered = deduplicateCandidates(candidates);

        if (legacyName.regionMatches(true, 0, "PE", 0, 2)
                && filtered.stream().anyMatch(candidate -> candidate.platformType() == PlatformType.FLOODGATE)) {
            filtered = filtered.stream()
                    .filter(candidate -> candidate.platformType() == PlatformType.FLOODGATE)
                    .toList();
        }

        boolean hasOffline = filtered.stream().anyMatch(candidate -> candidate.platformType() == PlatformType.JAVA_OFFLINE);
        boolean hasOnline = filtered.stream().anyMatch(candidate -> candidate.platformType() == PlatformType.JAVA_ONLINE);
        if (hasOffline && hasOnline) {
            filtered = filtered.stream()
                    .filter(candidate -> candidate.platformType() != PlatformType.JAVA_ONLINE)
                    .toList();
        }

        if (filtered.size() > 1 && filtered.stream().allMatch(candidate -> candidate.platformType() == PlatformType.JAVA_OFFLINE)) {
            int maxAssetCount = filtered.stream()
                    .mapToInt(NameCandidate::assetCount)
                    .max()
                    .orElse(0);
            long maxCountMatches = filtered.stream()
                    .filter(candidate -> candidate.assetCount() == maxAssetCount)
                    .count();
            if (maxAssetCount > 0 && maxCountMatches == 1) {
                filtered = filtered.stream()
                        .filter(candidate -> candidate.assetCount() == maxAssetCount)
                        .toList();
            }
        }

        return filtered;
    }

    private List<NameCandidate> deduplicateCandidates(List<NameCandidate> candidates) {
        Map<UUID, NameCandidate> deduplicated = new LinkedHashMap<>();
        for (NameCandidate candidate : candidates) {
            deduplicated.putIfAbsent(candidate.legacyUuid(), candidate);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public void close() {
    }

    public record NameMatchRow(UUID legacyUuid, String primaryName, ClaimStatus claimStatus) {
    }

    public record PlayerClaimRow(UUID legacyUuid, String primaryName, ClaimStatus claimStatus, String claimedAt) {
    }

    public record ClaimPromptCandidateRow(UUID legacyUuid, String legacyName, String primaryName, ClaimStatus claimStatus) {
    }

    public record ClaimedUuidBindingRow(UUID legacyUuid, UUID newUuid, String newName) {
    }

    public record AssetRow(UUID legacyUuid, String primaryName, String adapter, String assetKey) {
    }

    public record ClaimLogRow(
            String claimId,
            UUID legacyUuid,
            String scanId,
            String newName,
            String startedAt,
            String finishedAt,
            String status,
            String errorMessage
    ) {
    }

    public record IndexedAssetRow(UUID legacyUuid, String assetKey, String assetMetaJson) {
    }

    public record AccountSummaryRow(UUID legacyUuid, String primaryName) {
    }

    public record ClaimDetailRow(
            String claimId,
            UUID legacyUuid,
            String scanId,
            UUID newUuid,
            String newName,
            String startedAt,
            String finishedAt,
            String status,
            String errorMessage
    ) {
    }

    public record LegacyAccountRow(
            UUID legacyUuid,
            PlatformType platformType,
            String primaryName,
            ClaimStatus claimStatus,
            String claimedByUuid,
            String claimedByName,
            String claimedAt,
            String lastScanId
    ) {
    }

    public record UnlockResult(ClaimStatus restoredStatus) {
    }

    private record NameCandidateAccumulator(String displayName, List<NameCandidate> candidates) {
        private NameCandidateAccumulator(String displayName) {
            this(displayName, new ArrayList<>());
        }
    }

    private record NameCandidate(
            String legacyName,
            UUID legacyUuid,
            String primaryName,
            ClaimStatus claimStatus,
            PlatformType platformType,
            int assetCount
    ) {
    }
}
