package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.BukkitSyncUtil;
import cn.uuidmigrate.util.JdbcSnapshotStore;
import cn.uuidmigrate.util.JdbcTableUtil;
import cn.uuidmigrate.util.JsonFileUtil;
import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class XConomyAdapter implements MigrationAdapter {
    private static final String STATE_KEY = "xconomy.snapshot";
    private static final String SNAPSHOT_FILE = "adapter-xconomy-snapshot.json";

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final Gson gson = new Gson();

    public XConomyAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "xconomy";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "XConomy database", "plugins/XConomy/playerdata/data.db", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        Path databasePath = context.snapshotRoot().resolve("plugins/XConomy/playerdata/data.db");
        if (!Files.exists(databasePath)) {
            return;
        }

        List<UUID> floodgateAccounts = new ArrayList<>();
        try (Connection connection = openSQLite(databasePath)) {
            if (!JdbcTableUtil.tableExists(connection, "xconomy")) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement("SELECT UID, player FROM xconomy");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String rawUuid = resultSet.getString("UID");
                    if (rawUuid == null || rawUuid.isBlank()) {
                        continue;
                    }
                    UUID legacyUuid = UUID.fromString(rawUuid);
                    if (isFloodgateUuid(legacyUuid)) {
                        floodgateAccounts.add(legacyUuid);
                    }
                    context.registerAccount(legacyUuid, PlatformType.JAVA_OFFLINE);
                    context.registerName(legacyUuid, resultSet.getString("player"), "XCONOMY_PLAYER", true);
                    context.registerAsset(legacyUuid, key(), "xconomy:" + legacyUuid, null);
                }
            }
        }

        if (configService.config().skipFloodgatePlayers() && !floodgateAccounts.isEmpty()) {
            restoreFloodgateBalancesToLive(databasePath, floodgateAccounts);
        }
    }

    @Override
    public void validate(ClaimContext context) throws Exception {
        if (!hasIndexedAssets(context)) {
            return;
        }

        Path databasePath = liveDatabasePath(context);
        if (!Files.exists(databasePath)) {
            throw new IllegalStateException("XConomy live database is missing: " + databasePath);
        }
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        Path databasePath = liveDatabasePath(context);
        if (!Files.exists(databasePath)) {
            return;
        }

        Snapshot legacySnapshot = loadLegacySnapshot(context);

        try (Connection connection = openSQLite(databasePath)) {
            connection.setAutoCommit(false);
            try {
                Snapshot rollbackSnapshot = createSnapshot(connection, context);
                context.state().put(STATE_KEY, rollbackSnapshot);
                saveSnapshot(context, rollbackSnapshot);

                migrateAccountTable(connection, context, legacySnapshot);
                migrateRecordTable(connection, context, legacySnapshot);

                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }

        refreshRuntimeCache(context);
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        Snapshot snapshot = loadSnapshot(context);
        if (snapshot == null) {
            return;
        }

        Path databasePath = liveDatabasePath(context);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openSQLite(databasePath)) {
            connection.setAutoCommit(false);
            try {
                if (snapshot.accountSnapshot() != null) {
                    JdbcTableUtil.deleteWhere(connection, "xconomy", "UID = ? OR UID = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
                    JdbcTableUtil.insertSnapshot(connection, snapshot.accountSnapshot());
                }

                if (snapshot.recordSnapshot() != null) {
                    JdbcTableUtil.deleteWhere(connection, "xconomyrecord", buildRecordWhereClause(snapshot.recordHasUid(), snapshot.recordHasPlayer()), buildRecordParameters(context, snapshot.recordHasUid(), snapshot.recordHasPlayer()));
                    JdbcTableUtil.insertSnapshot(connection, snapshot.recordSnapshot());
                }

                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private Snapshot loadLegacySnapshot(ClaimContext context) throws Exception {
        Path snapshotDatabasePath = context.config().snapshotRoot().resolve("plugins/XConomy/playerdata/data.db");
        if (!Files.exists(snapshotDatabasePath)) {
            return new Snapshot(null, null, false, false);
        }
        try (Connection connection = openSQLite(snapshotDatabasePath)) {
            return createSnapshot(connection, context);
        }
    }

    private Snapshot createSnapshot(Connection connection, ClaimContext context) throws Exception {
        JdbcTableUtil.TableSnapshot accountSnapshot = null;
        JdbcTableUtil.TableSnapshot recordSnapshot = null;
        boolean recordHasUid = false;
        boolean recordHasPlayer = false;

        if (JdbcTableUtil.tableExists(connection, "xconomy") && JdbcTableUtil.columnExists(connection, "xconomy", "UID")) {
            accountSnapshot = JdbcTableUtil.snapshotTable(connection, "xconomy", "UID = ? OR UID = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
        }

        if (JdbcTableUtil.tableExists(connection, "xconomyrecord")) {
            recordHasUid = JdbcTableUtil.columnExists(connection, "xconomyrecord", "UID");
            recordHasPlayer = JdbcTableUtil.columnExists(connection, "xconomyrecord", "player");
            if (recordHasUid || recordHasPlayer) {
                recordSnapshot = JdbcTableUtil.snapshotTable(
                        connection,
                        "xconomyrecord",
                        buildRecordWhereClause(recordHasUid, recordHasPlayer),
                        buildRecordParameters(context, recordHasUid, recordHasPlayer)
                );
            }
        }

        return new Snapshot(accountSnapshot, recordSnapshot, recordHasUid, recordHasPlayer);
    }

    private void saveSnapshot(ClaimContext context, Snapshot snapshot) throws Exception {
        JsonFileUtil.writeJson(
                context.backupRoot().resolve(SNAPSHOT_FILE),
                gson,
                new StoredSnapshot(
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.accountSnapshot()),
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.recordSnapshot()),
                        snapshot.recordHasUid(),
                        snapshot.recordHasPlayer()
                )
        );
    }

    private Snapshot loadSnapshot(ClaimContext context) throws Exception {
        Object rawSnapshot = context.state().get(STATE_KEY);
        if (rawSnapshot instanceof Snapshot snapshot) {
            return snapshot;
        }

        Path snapshotPath = context.backupRoot().resolve(SNAPSHOT_FILE);
        if (!Files.exists(snapshotPath)) {
            return null;
        }

        StoredSnapshot stored = JsonFileUtil.readJson(snapshotPath, gson, StoredSnapshot.class);
        Snapshot snapshot = new Snapshot(
                JdbcSnapshotStore.toTableSnapshot(stored.accountSnapshot()),
                JdbcSnapshotStore.toTableSnapshot(stored.recordSnapshot()),
                stored.recordHasUid(),
                stored.recordHasPlayer()
        );
        context.state().put(STATE_KEY, snapshot);
        return snapshot;
    }

    private void migrateAccountTable(Connection connection, ClaimContext context, Snapshot snapshot) throws Exception {
        if (!JdbcTableUtil.tableExists(connection, "xconomy") || !JdbcTableUtil.columnExists(connection, "xconomy", "UID")) {
            return;
        }
        if (snapshot.accountSnapshot() == null || snapshot.accountSnapshot().rows().isEmpty()) {
            return;
        }

        JdbcTableUtil.deleteWhere(connection, "xconomy", "UID = ? OR UID = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
        JdbcTableUtil.insertSnapshot(connection, remapSnapshot(snapshot.accountSnapshot(), context, true));
    }

    private void migrateRecordTable(Connection connection, ClaimContext context, Snapshot snapshot) throws Exception {
        if (!JdbcTableUtil.tableExists(connection, "xconomyrecord")) {
            return;
        }
        if (snapshot.recordSnapshot() == null || snapshot.recordSnapshot().rows().isEmpty()) {
            return;
        }

        JdbcTableUtil.deleteWhere(
                connection,
                "xconomyrecord",
                buildRecordWhereClause(snapshot.recordHasUid(), snapshot.recordHasPlayer()),
                buildRecordParameters(context, snapshot.recordHasUid(), snapshot.recordHasPlayer())
        );
        JdbcTableUtil.insertSnapshot(connection, remapSnapshot(snapshot.recordSnapshot(), context, false));
    }

    private JdbcTableUtil.TableSnapshot remapSnapshot(JdbcTableUtil.TableSnapshot snapshot, ClaimContext context, boolean preferExactPlayerName) {
        if (snapshot == null) {
            return null;
        }

        int uidIndex = snapshot.columns().indexOf("UID");
        int playerIndex = snapshot.columns().indexOf("player");
        List<JdbcTableUtil.TableRow> remappedRows = new ArrayList<>();
        for (JdbcTableUtil.TableRow row : snapshot.rows()) {
            List<Object> values = new ArrayList<>(row.values());
            if (uidIndex >= 0) {
                values.set(uidIndex, context.newUuid().toString());
            }
            if (playerIndex >= 0 && (preferExactPlayerName || values.get(playerIndex) != null)) {
                values.set(playerIndex, context.newName());
            }
            remappedRows.add(new JdbcTableUtil.TableRow(values));
        }
        return new JdbcTableUtil.TableSnapshot(snapshot.tableName(), snapshot.columns(), remappedRows);
    }

    private String buildRecordWhereClause(boolean hasUid, boolean hasPlayer) {
        List<String> clauses = new ArrayList<>();
        if (hasUid) {
            clauses.add("UID = ? OR UID = ?");
        }
        if (hasPlayer) {
            clauses.add("player = ? OR player = ?");
        }
        return String.join(" OR ", clauses);
    }

    private List<String> buildRecordParameters(ClaimContext context, boolean hasUid, boolean hasPlayer) {
        List<String> parameters = new ArrayList<>();
        if (hasUid) {
            parameters.add(context.legacyUuid().toString());
            parameters.add(context.newUuid().toString());
        }
        if (hasPlayer) {
            parameters.add(context.legacyName());
            parameters.add(context.newName());
        }
        return parameters;
    }

    private Connection openSQLite(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize());
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA busy_timeout = 5000")) {
            statement.execute();
        }
        return connection;
    }

    private Path liveDatabasePath(ClaimContext context) {
        return context.config().serverRoot().resolve("plugins/XConomy/playerdata/data.db");
    }

    private void refreshRuntimeCache(ClaimContext context) throws Exception {
        BukkitSyncUtil.run(plugin, () -> {
            var targetPlugin = plugin.getServer().getPluginManager().getPlugin("XConomy");
            if (targetPlugin == null || !targetPlugin.isEnabled()) {
                return;
            }

            ClassLoader loader = targetPlugin.getClass().getClassLoader();
            Class<?> cacheClass = Class.forName("me.yic.xconomy.data.caches.Cache", true, loader);
            Method removeFromCache = cacheClass.getMethod("removefromCache", UUID.class);
            Method deleteDataFromCache = cacheClass.getMethod("deleteDataFromCache", UUID.class);
            Method syncOnlineUuidCache = cacheClass.getMethod("syncOnlineUUIDCache", String.class, String.class, UUID.class);
            Method clearCache = cacheClass.getMethod("clearCache");
            removeFromCache.invoke(null, context.legacyUuid());
            removeFromCache.invoke(null, context.newUuid());
            deleteDataFromCache.invoke(null, context.legacyUuid());
            deleteDataFromCache.invoke(null, context.newUuid());
            syncOnlineUuidCache.invoke(null, context.legacyName(), context.newName(), context.newUuid());
            clearCache.invoke(null);

            Class<?> apiClass = Class.forName("me.yic.xconomy.api.XConomyAPI", true, loader);
            Object api = apiClass.getConstructor().newInstance();
            apiClass.getMethod("getPlayerData", UUID.class).invoke(api, context.newUuid());
        });
    }

    private boolean hasIndexedAssets(ClaimContext context) throws Exception {
        return !context.indexDatabase().loadAdapterAssets(context.scanId(), key(), context.legacyUuid()).isEmpty();
    }

    private void restoreFloodgateBalancesToLive(Path snapshotDatabasePath, List<UUID> floodgateAccounts) throws Exception {
        Path liveDatabasePath = configService.config().serverRoot().resolve("plugins/XConomy/playerdata/data.db");
        if (!Files.exists(liveDatabasePath)) {
            return;
        }

        List<String> floodgateUuids = floodgateAccounts.stream()
                .map(UUID::toString)
                .distinct()
                .toList();
        if (floodgateUuids.isEmpty()) {
            return;
        }

        try (Connection snapshotConnection = openSQLite(snapshotDatabasePath);
             Connection liveConnection = openSQLite(liveDatabasePath)) {
            if (!JdbcTableUtil.tableExists(snapshotConnection, "xconomy")
                    || !JdbcTableUtil.columnExists(snapshotConnection, "xconomy", "UID")
                    || !JdbcTableUtil.tableExists(liveConnection, "xconomy")
                    || !JdbcTableUtil.columnExists(liveConnection, "xconomy", "UID")) {
                return;
            }

            JdbcTableUtil.TableSnapshot floodgateSnapshot = snapshotRowsByUuids(snapshotConnection, "xconomy", floodgateUuids);
            if (floodgateSnapshot == null || floodgateSnapshot.rows().isEmpty()) {
                return;
            }

            liveConnection.setAutoCommit(false);
            try {
                JdbcTableUtil.deleteWhere(liveConnection, "xconomy", buildUuidWhereClause(floodgateUuids.size()), floodgateUuids);
                JdbcTableUtil.insertSnapshot(liveConnection, floodgateSnapshot);
                liveConnection.commit();
            } catch (Exception exception) {
                liveConnection.rollback();
                throw exception;
            } finally {
                liveConnection.setAutoCommit(true);
            }
        }
    }

    private JdbcTableUtil.TableSnapshot snapshotRowsByUuids(Connection connection, String tableName, List<String> uuids) throws Exception {
        if (uuids.isEmpty()) {
            return null;
        }
        return JdbcTableUtil.snapshotTable(connection, tableName, buildUuidWhereClause(uuids.size()), uuids);
    }

    private String buildUuidWhereClause(int size) {
        List<String> clauses = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            clauses.add("UID = ?");
        }
        return String.join(" OR ", clauses);
    }

    private boolean isFloodgateUuid(UUID uuid) {
        return uuid != null && uuid.toString().regionMatches(true, 0, "00000000-0000-0000-0009-", 0, "00000000-0000-0000-0009-".length());
    }

    private boolean snapshotContainsUuid(JdbcTableUtil.TableSnapshot snapshot, String columnName, UUID uuid) {
        if (snapshot == null) {
            return false;
        }
        int columnIndex = snapshot.columns().indexOf(columnName);
        if (columnIndex < 0) {
            return false;
        }
        return snapshot.rows().stream()
                .map(row -> row.values().get(columnIndex))
                .filter(value -> value != null)
                .map(Object::toString)
                .anyMatch(uuid.toString()::equals);
    }

    private record Snapshot(
            JdbcTableUtil.TableSnapshot accountSnapshot,
            JdbcTableUtil.TableSnapshot recordSnapshot,
            boolean recordHasUid,
            boolean recordHasPlayer
    ) {
    }

    private record StoredSnapshot(
            JdbcSnapshotStore.StoredTableSnapshot accountSnapshot,
            JdbcSnapshotStore.StoredTableSnapshot recordSnapshot,
            boolean recordHasUid,
            boolean recordHasPlayer
    ) {
    }
}
