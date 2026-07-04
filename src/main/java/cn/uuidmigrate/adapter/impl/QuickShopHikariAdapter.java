package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.PrepareAwareAdapter;
import cn.uuidmigrate.adapter.PrepareContext;
import cn.uuidmigrate.adapter.PrepareResult;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.BukkitSyncUtil;
import cn.uuidmigrate.util.JdbcSnapshotStore;
import cn.uuidmigrate.util.JdbcTableUtil;
import cn.uuidmigrate.util.JsonFileUtil;
import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class QuickShopHikariAdapter implements MigrationAdapter, PrepareAwareAdapter {
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final String QUICKSHOP_PLUGIN_NAME = "QuickShop-Hikari";
    private static final String CLAIM_STATE_KEY = "quickshop.claim.snapshot";
    private static final String PREPARE_STATE_KEY = "quickshop.prepare.snapshot";
    private static final String CLAIM_SNAPSHOT_FILE = "adapter-quickshop-claim-snapshot.json";

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final Gson gson = new Gson();

    public QuickShopHikariAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "quickshop";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "QuickShop-Hikari database", "plugins/QuickShop-Hikari/shops.mv.db", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        Path databasePath = snapshotDatabasePath(context);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openH2(databasePath)) {
            QuickShopTables tables = resolveTables(connection);
            if (tables == null) {
                return;
            }

            Map<UUID, String> cachedNames = loadCachedNames(connection, tables.playersTable());
            try (PreparedStatement statement = connection.prepareStatement("SELECT id, owner FROM " + tables.dataTable());
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID legacyUuid = parseUuid(resultSet.getString("owner"));
                    if (legacyUuid == null) {
                        continue;
                    }

                    long shopId = resultSet.getLong("id");
                    context.registerAccount(legacyUuid, PlatformType.JAVA_OFFLINE);
                    context.registerName(legacyUuid, cachedNames.get(legacyUuid), "QUICKSHOP_CACHED_NAME", true);
                    context.registerAsset(legacyUuid, key(), "quickshop:" + shopId, gson.toJson(new QuickShopAsset(shopId)));
                }
            }
        }
    }

    @Override
    public PrepareResult prepare(PrepareContext context) throws Exception {
        List<QuickShopBinding> bindings = loadBindings(context.indexDatabase(), context.scanId());
        if (bindings.isEmpty()) {
            return new PrepareResult(key(), 0, 0, 0, List.of("最近一次扫描中没有找到 QuickShop 商店记录。"));
        }

        Path databasePath = liveDatabasePath(context.config().serverRoot());
        if (!Files.exists(databasePath)) {
            throw new IllegalStateException("QuickShop-Hikari database not found: " + databasePath);
        }

        try (Connection connection = openLiveConnection(databasePath)) {
                QuickShopTables tables = requireTables(connection);
                connection.setAutoCommit(false);
                try {
                    Snapshot snapshot = snapshotForPrepare(connection, tables, bindings, context.config().quickshopHolderUuid());
                    context.state().put(PREPARE_STATE_KEY, snapshot);

                    ensurePlayerProfile(
                            connection,
                            tables.playersTable(),
                            context.config().quickshopHolderUuid(),
                            preferredLocale(connection, tables.playersTable(), bindings.stream().map(QuickShopBinding::legacyUuid).toList()).orElse("en_us"),
                            context.config().quickshopHolderName()
                    );

                    Map<UUID, List<Long>> grouped = groupShopIds(bindings);
                    int changedCount = 0;
                    for (Map.Entry<UUID, List<Long>> entry : grouped.entrySet()) {
                        changedCount += updateOwners(
                                connection,
                                tables.dataTable(),
                                entry.getValue(),
                                context.config().quickshopHolderUuid().toString(),
                                List.of(entry.getKey().toString())
                        );
                    }

                    connection.commit();
                    return new PrepareResult(
                            key(),
                            bindings.size(),
                            changedCount,
                            1,
                            List.of(
                                "映射到旧所有者的人数: " + grouped.size(),
                                "临时占位 UUID: " + context.config().quickshopHolderUuid(),
                                "QuickShop 连接来源: " + connectionSource(connection)
                            )
                            );
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
        }
    }

    @Override
    public void rollbackPrepare(PrepareContext context) throws Exception {
        Object rawSnapshot = context.state().get(PREPARE_STATE_KEY);
        if (!(rawSnapshot instanceof Snapshot snapshot)) {
            return;
        }

        Path databasePath = liveDatabasePath(context.config().serverRoot());
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openLiveConnection(databasePath)) {
            connection.setAutoCommit(false);
            try {
                restoreSnapshot(connection, snapshot);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        List<QuickShopBinding> bindings = loadBindings(context.indexDatabase(), context.scanId(), context.legacyUuid());
        if (bindings.isEmpty()) {
            return;
        }

        Path databasePath = liveDatabasePath(context.config().serverRoot());
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openLiveConnection(databasePath)) {
                QuickShopTables tables = requireTables(connection);
                connection.setAutoCommit(false);
                try {
                    Snapshot snapshot = snapshotForClaim(connection, tables, bindings, context);
                    context.state().put(CLAIM_STATE_KEY, snapshot);
                    saveClaimSnapshot(context, snapshot);

                    updateOwners(
                            connection,
                            tables.dataTable(),
                            bindings.stream().map(QuickShopBinding::shopId).distinct().toList(),
                            context.newUuid().toString(),
                            List.of(context.legacyUuid().toString(), context.config().quickshopHolderUuid().toString())
                    );
                    updatePermissionsForClaim(
                            connection,
                            tables.dataTable(),
                            bindings.stream().map(QuickShopBinding::shopId).distinct().toList(),
                            List.of(context.legacyUuid().toString(), context.config().quickshopHolderUuid().toString()),
                            context.newUuid().toString()
                    );

                    updatePlayerProfileForClaim(connection, tables.playersTable(), context);
                    connection.commit();
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
        }

        refreshRuntimeShops(context, bindings);
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        Snapshot snapshot = loadClaimSnapshot(context);
        if (snapshot == null) {
            return;
        }

        Path databasePath = liveDatabasePath(context.config().serverRoot());
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openLiveConnection(databasePath)) {
            connection.setAutoCommit(false);
            try {
                restoreSnapshot(connection, snapshot);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private List<QuickShopBinding> loadBindings(IndexDatabase indexDatabase, String scanId) throws Exception {
        List<QuickShopBinding> bindings = new ArrayList<>();
        for (IndexDatabase.IndexedAssetRow row : indexDatabase.loadAdapterAssets(scanId, key())) {
            if (shouldSkipFloodgateBinding(indexDatabase, row.legacyUuid())) {
                continue;
            }
            QuickShopAsset asset = parseAsset(row.assetMetaJson(), row.assetKey());
            if (asset != null) {
                bindings.add(new QuickShopBinding(row.legacyUuid(), asset.shopId()));
            }
        }
        return dedupeBindings(bindings);
    }

    private List<QuickShopBinding> loadBindings(IndexDatabase indexDatabase, String scanId, UUID legacyUuid) throws Exception {
        if (shouldSkipFloodgateBinding(indexDatabase, legacyUuid)) {
            return List.of();
        }
        List<QuickShopBinding> bindings = new ArrayList<>();
        for (IndexDatabase.IndexedAssetRow row : indexDatabase.loadAdapterAssets(scanId, key(), legacyUuid)) {
            QuickShopAsset asset = parseAsset(row.assetMetaJson(), row.assetKey());
            if (asset != null) {
                bindings.add(new QuickShopBinding(row.legacyUuid(), asset.shopId()));
            }
        }
        return dedupeBindings(bindings);
    }

    private boolean shouldSkipFloodgateBinding(IndexDatabase indexDatabase, UUID legacyUuid) throws Exception {
        if (!configService.config().skipFloodgatePlayers()) {
            return false;
        }
        return indexDatabase.findLegacyAccount(legacyUuid)
                .map(account -> account.platformType() == PlatformType.FLOODGATE)
                .orElse(false);
    }

    private List<QuickShopBinding> dedupeBindings(List<QuickShopBinding> bindings) {
        Set<String> seen = new LinkedHashSet<>();
        List<QuickShopBinding> deduped = new ArrayList<>();
        for (QuickShopBinding binding : bindings) {
            String uniqueKey = binding.legacyUuid() + ":" + binding.shopId();
            if (seen.add(uniqueKey)) {
                deduped.add(binding);
            }
        }
        return deduped;
    }

    private QuickShopAsset parseAsset(String rawJson, String assetKey) {
        if (rawJson != null && !rawJson.isBlank()) {
            try {
                QuickShopAsset asset = gson.fromJson(rawJson, QuickShopAsset.class);
                if (asset != null) {
                    return asset;
                }
            } catch (Exception ignored) {
            }
        }

        if (assetKey != null && assetKey.startsWith("quickshop:")) {
            try {
                return new QuickShopAsset(Long.parseLong(assetKey.substring("quickshop:".length())));
            } catch (NumberFormatException ignored) {
            }
        }

        plugin.getLogger().warning("[meta:" + key() + "] Failed to parse QuickShop asset: " + assetKey);
        return null;
    }

    private Map<UUID, List<Long>> groupShopIds(List<QuickShopBinding> bindings) {
        Map<UUID, List<Long>> grouped = new LinkedHashMap<>();
        for (QuickShopBinding binding : bindings) {
            grouped.computeIfAbsent(binding.legacyUuid(), ignored -> new ArrayList<>()).add(binding.shopId());
        }
        return grouped;
    }

    private Snapshot snapshotForPrepare(Connection connection, QuickShopTables tables, List<QuickShopBinding> bindings, UUID holderUuid) throws Exception {
        List<Long> shopIds = bindings.stream().map(QuickShopBinding::shopId).distinct().toList();
        List<String> playerUuids = List.of(holderUuid.toString());
        return new Snapshot(
                tables,
                snapshotRows(connection, tables.dataTable(), "id", shopIds),
                snapshotRows(connection, tables.playersTable(), "uuid", playerUuids),
                shopIds,
                playerUuids
        );
    }

    private Snapshot snapshotForClaim(Connection connection, QuickShopTables tables, List<QuickShopBinding> bindings, ClaimContext context) throws Exception {
        List<Long> shopIds = bindings.stream().map(QuickShopBinding::shopId).distinct().toList();
        List<String> playerUuids = List.of(
                context.legacyUuid().toString(),
                context.newUuid().toString(),
                context.config().quickshopHolderUuid().toString()
        );
        return new Snapshot(
                tables,
                snapshotRows(connection, tables.dataTable(), "id", shopIds),
                snapshotRows(connection, tables.playersTable(), "uuid", playerUuids),
                shopIds,
                playerUuids
        );
    }

    private JdbcTableUtil.TableSnapshot snapshotRows(Connection connection, String tableName, String columnName, List<?> values) throws Exception {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return JdbcTableUtil.snapshotTable(connection, tableName, buildInClause(columnName, values.size()), values);
    }

    private void saveClaimSnapshot(ClaimContext context, Snapshot snapshot) throws Exception {
        JsonFileUtil.writeJson(
                context.backupRoot().resolve(CLAIM_SNAPSHOT_FILE),
                gson,
                new StoredSnapshot(
                        snapshot.tables(),
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.dataSnapshot()),
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.playerSnapshot()),
                        snapshot.shopIds(),
                        snapshot.playerUuids()
                )
        );
    }

    private Snapshot loadClaimSnapshot(ClaimContext context) throws Exception {
        Object rawSnapshot = context.state().get(CLAIM_STATE_KEY);
        if (rawSnapshot instanceof Snapshot snapshot) {
            return snapshot;
        }

        Path snapshotPath = context.backupRoot().resolve(CLAIM_SNAPSHOT_FILE);
        if (!Files.exists(snapshotPath)) {
            return null;
        }

        StoredSnapshot stored = JsonFileUtil.readJson(snapshotPath, gson, StoredSnapshot.class);
        Snapshot snapshot = new Snapshot(
                stored.tables(),
                JdbcSnapshotStore.toTableSnapshot(stored.dataSnapshot()),
                JdbcSnapshotStore.toTableSnapshot(stored.playerSnapshot()),
                stored.shopIds(),
                stored.playerUuids()
        );
        context.state().put(CLAIM_STATE_KEY, snapshot);
        return snapshot;
    }

    private void restoreSnapshot(Connection connection, Snapshot snapshot) throws Exception {
        if (!snapshot.shopIds().isEmpty()) {
            JdbcTableUtil.deleteWhere(connection, snapshot.tables().dataTable(), buildInClause("id", snapshot.shopIds().size()), snapshot.shopIds());
            if (snapshot.dataSnapshot() != null) {
                JdbcTableUtil.insertSnapshot(connection, snapshot.dataSnapshot());
            }
        }

        if (!snapshot.playerUuids().isEmpty()) {
            JdbcTableUtil.deleteWhere(connection, snapshot.tables().playersTable(), buildInClause("uuid", snapshot.playerUuids().size()), snapshot.playerUuids());
            if (snapshot.playerSnapshot() != null) {
                JdbcTableUtil.insertSnapshot(connection, snapshot.playerSnapshot());
            }
        }
    }

    private int updateOwners(Connection connection, String dataTable, List<Long> shopIds, String newOwner, List<String> sourceOwners) throws SQLException {
        if (shopIds.isEmpty() || sourceOwners.isEmpty()) {
            return 0;
        }

        String sql = "UPDATE " + dataTable + " SET owner = ? WHERE " + buildInClause("id", shopIds.size()) + " AND " + buildInClause("owner", sourceOwners.size());
        List<Object> parameters = new ArrayList<>();
        parameters.add(newOwner);
        parameters.addAll(shopIds);
        parameters.addAll(sourceOwners);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcTableUtil.bindParameters(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private void updatePermissionsForClaim(
            Connection connection,
            String dataTable,
            List<Long> shopIds,
            List<String> sourceUuids,
            String targetUuid
    ) throws Exception {
        if (shopIds.isEmpty() || sourceUuids.isEmpty() || !JdbcTableUtil.columnExists(connection, dataTable, "permissions")) {
            return;
        }

        String selectSql = "SELECT id, permissions FROM " + dataTable + " WHERE " + buildInClause("id", shopIds.size());
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            JdbcTableUtil.bindParameters(statement, new ArrayList<>(shopIds));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long shopId = resultSet.getLong("id");
                    String permissions = resultSet.getString("permissions");
                    String replaced = replaceUuidReferences(permissions, sourceUuids, targetUuid);
                    if (permissions == null || permissions.equals(replaced)) {
                        continue;
                    }

                    try (PreparedStatement update = connection.prepareStatement("UPDATE " + dataTable + " SET permissions = ? WHERE id = ?")) {
                        update.setString(1, replaced);
                        update.setLong(2, shopId);
                        update.executeUpdate();
                    }
                }
            }
        }
    }

    private void ensurePlayerProfile(Connection connection, String playersTable, UUID uuid, String locale, String cachedName) throws Exception {
        PlayerProfile existing = loadPlayerProfile(connection, playersTable, uuid).orElse(null);
        if (existing != null) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE " + playersTable + " SET locale = ?, cachedName = ? WHERE uuid = ?")) {
                statement.setString(1, trimToNull(existing.locale()) == null ? locale : existing.locale());
                statement.setString(2, cachedName);
                statement.setString(3, uuid.toString());
                statement.executeUpdate();
            }
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + playersTable + " (uuid, locale, cachedName) VALUES (?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, locale);
            statement.setString(3, cachedName);
            statement.executeUpdate();
        }
    }

    private void updatePlayerProfileForClaim(Connection connection, String playersTable, ClaimContext context) throws Exception {
        PlayerProfile legacyProfile = loadPlayerProfile(connection, playersTable, context.legacyUuid()).orElse(null);
        PlayerProfile newProfile = loadPlayerProfile(connection, playersTable, context.newUuid()).orElse(null);
        PlayerProfile holderProfile = loadPlayerProfile(connection, playersTable, context.config().quickshopHolderUuid()).orElse(null);
        String locale = firstNonBlank(
                newProfile == null ? null : newProfile.locale(),
                legacyProfile == null ? null : legacyProfile.locale(),
                holderProfile == null ? null : holderProfile.locale(),
                "en_us"
        );

        if (legacyProfile != null) {
            if (!context.legacyUuid().equals(context.newUuid())) {
                deletePlayerProfile(connection, playersTable, context.newUuid());
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE " + playersTable + " SET uuid = ?, locale = ?, cachedName = ? WHERE uuid = ?")) {
                statement.setString(1, context.newUuid().toString());
                statement.setString(2, locale);
                statement.setString(3, context.newName());
                statement.setString(4, context.legacyUuid().toString());
                int updated = statement.executeUpdate();
                if (updated > 0) {
                    return;
                }
            }
        }

        ensurePlayerProfile(connection, playersTable, context.newUuid(), locale, context.newName());
    }

    private void deletePlayerProfile(Connection connection, String playersTable, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + playersTable + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    private Optional<PlayerProfile> loadPlayerProfile(Connection connection, String playersTable, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, locale, cachedName FROM " + playersTable + " WHERE uuid = ? LIMIT 1")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new PlayerProfile(
                            resultSet.getString("uuid"),
                            resultSet.getString("locale"),
                            resultSet.getString("cachedName")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> preferredLocale(Connection connection, String playersTable, Collection<UUID> uuids) throws Exception {
        for (UUID uuid : uuids) {
            Optional<PlayerProfile> profile = loadPlayerProfile(connection, playersTable, uuid);
            if (profile.isPresent() && trimToNull(profile.get().locale()) != null) {
                return Optional.of(profile.get().locale());
            }
        }
        return Optional.empty();
    }

    private Map<UUID, String> loadCachedNames(Connection connection, String playersTable) throws SQLException {
        Map<UUID, String> names = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, cachedName FROM " + playersTable);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID uuid = parseUuid(resultSet.getString("uuid"));
                if (uuid != null) {
                    names.put(uuid, resultSet.getString("cachedName"));
                }
            }
        }
        return names;
    }

    private QuickShopTables requireTables(Connection connection) throws Exception {
        QuickShopTables tables = resolveTables(connection);
        if (tables == null) {
            throw new IllegalStateException("QuickShop-Hikari data/players tables could not be identified.");
        }
        return tables;
    }

    private QuickShopTables resolveTables(Connection connection) throws Exception {
        String dataTable = null;
        String playersTable = null;
        for (String table : JdbcTableUtil.listTables(connection)) {
            if (dataTable == null
                    && JdbcTableUtil.columnExists(connection, table, "id")
                    && JdbcTableUtil.columnExists(connection, table, "owner")
                    && JdbcTableUtil.columnExists(connection, table, "item")) {
                dataTable = table;
            }
            if (playersTable == null
                    && JdbcTableUtil.columnExists(connection, table, "uuid")
                    && JdbcTableUtil.columnExists(connection, table, "cachedName")
                    && JdbcTableUtil.columnExists(connection, table, "locale")) {
                playersTable = table;
            }
        }
        return dataTable == null || playersTable == null ? null : new QuickShopTables(dataTable, playersTable);
    }

    private Connection openH2(Path databasePath) throws Exception {
        Class.forName("org.h2.Driver");
        String filePath = stripDatabaseSuffix(databasePath.toAbsolutePath().normalize().toString()).replace('\\', '/');
        return DriverManager.getConnection("jdbc:h2:file:" + filePath);
    }

    private Connection openLiveConnection(Path databasePath) throws Exception {
        Object manager = resolveQuickShopSqlManager();
        if (manager != null) {
            Method getConnection = manager.getClass().getMethod("getConnection");
            Object rawConnection = getConnection.invoke(manager);
            if (rawConnection instanceof Connection connection) {
                plugin.getLogger().info("[quickshop] 正在使用 QuickShop-Hikari 托管连接访问在线数据库。");
                return connection;
            }
        }

        plugin.getLogger().warning("[quickshop] Falling back to direct H2 file connection. QuickShop managed connection was unavailable.");
        return openH2(databasePath);
    }

    private Object resolveQuickShopSqlManager() {
        try {
            var quickShopPlugin = plugin.getServer().getPluginManager().getPlugin(QUICKSHOP_PLUGIN_NAME);
            if (quickShopPlugin == null || !quickShopPlugin.isEnabled()) {
                return null;
            }

            ClassLoader loader = quickShopPlugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI", true, loader);
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return null;
            }

            Object databaseHelper = apiClass.getMethod("getDatabaseHelper").invoke(api);
            if (databaseHelper == null) {
                return null;
            }

            Method getManager = databaseHelper.getClass().getMethod("getManager");
            return getManager.invoke(databaseHelper);
        } catch (Exception exception) {
            plugin.getLogger().warning("[quickshop] Failed to resolve QuickShop SQL manager: " + exception.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void refreshRuntimeShops(ClaimContext context, List<QuickShopBinding> bindings) throws Exception {
        BukkitSyncUtil.run(plugin, () -> {
            var quickShopPlugin = plugin.getServer().getPluginManager().getPlugin(QUICKSHOP_PLUGIN_NAME);
            if (quickShopPlugin == null || !quickShopPlugin.isEnabled()) {
                return;
            }

            ClassLoader loader = quickShopPlugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("com.ghostchu.quickshop.api.QuickShopAPI", true, loader);
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return;
            }

            Object shopManager = apiClass.getMethod("getShopManager").invoke(api);
            Object playerFinder = apiClass.getMethod("getPlayerFinder").invoke(api);
            if (shopManager == null || playerFinder == null) {
                return;
            }

            playerFinder.getClass().getMethod("cache", UUID.class, String.class).invoke(playerFinder, context.newUuid(), context.newName());

            Class<?> qUserClass = Class.forName("com.ghostchu.quickshop.api.obj.QUser", true, loader);
            Class<?> qUserImplClass = Class.forName("com.ghostchu.quickshop.obj.QUserImpl", true, loader);
            Object newOwner = qUserImplClass.getMethod("createFullFilled", UUID.class, String.class, boolean.class)
                    .invoke(null, context.newUuid(), context.newName(), true);

            Method getShop = shopManager.getClass().getMethod("getShop", long.class);
            Method getAllShops = shopManager.getClass().getMethod("getAllShops");
            Method setOwner = null;
            Method getPermissionAudiences = null;
            Method setPlayerGroup = null;
            Method updateSync = null;
            Method setSignText = null;
            Method setDirty = null;

            Set<Long> targetedShopIds = new LinkedHashSet<>();
            for (QuickShopBinding binding : bindings) {
                targetedShopIds.add(binding.shopId());
            }

            int refreshed = 0;
            Set<Long> refreshedIds = new LinkedHashSet<>();
            for (long shopId : targetedShopIds) {
                Object shop = getShop.invoke(shopManager, shopId);
                if (shop == null) {
                    continue;
                }
                if (setOwner == null) {
                    setOwner = shop.getClass().getMethod("setOwner", qUserClass);
                    getPermissionAudiences = shop.getClass().getMethod("getPermissionAudiences");
                    setPlayerGroup = shop.getClass().getMethod("setPlayerGroup", UUID.class, String.class);
                    updateSync = shop.getClass().getMethod("updateSync");
                    setSignText = shop.getClass().getMethod("setSignText");
                    setDirty = shop.getClass().getMethod("setDirty", boolean.class);
                }
                if (refreshRuntimeShopObject(context, shop, newOwner, setOwner, getPermissionAudiences, setPlayerGroup, setSignText, setDirty, updateSync)) {
                    refreshed++;
                    refreshedIds.add(shopId);
                }
            }

            for (Object shop : (List<Object>) getAllShops.invoke(shopManager)) {
                if (shop == null) {
                    continue;
                }
                long shopId = ((Number) shop.getClass().getMethod("getShopId").invoke(shop)).longValue();
                if (!targetedShopIds.contains(shopId) || refreshedIds.contains(shopId)) {
                    continue;
                }
                if (setOwner == null) {
                    setOwner = shop.getClass().getMethod("setOwner", qUserClass);
                    getPermissionAudiences = shop.getClass().getMethod("getPermissionAudiences");
                    setPlayerGroup = shop.getClass().getMethod("setPlayerGroup", UUID.class, String.class);
                    updateSync = shop.getClass().getMethod("updateSync");
                    setSignText = shop.getClass().getMethod("setSignText");
                    setDirty = shop.getClass().getMethod("setDirty", boolean.class);
                }
                if (refreshRuntimeShopObject(context, shop, newOwner, setOwner, getPermissionAudiences, setPlayerGroup, setSignText, setDirty, updateSync)) {
                    refreshed++;
                }
            }

            if (refreshed > 0) {
                plugin.getLogger().info("[quickshop] Refreshed " + refreshed + " runtime shop instance(s) after claim.");
            }

            try {
                Method reloadModule = api.getClass().getMethod("reloadModule");
                Object reloadResult = reloadModule.invoke(api);
                plugin.getLogger().info("[quickshop] Reloaded QuickShop core after claim: " + String.valueOf(reloadResult));

                try {
                    Object reloadedShopManager = apiClass.getMethod("getShopManager").invoke(api);
                    if (reloadedShopManager != null) {
                        reloadedShopManager.getClass().getMethod("clear").invoke(reloadedShopManager);
                    }
                    Method getShopLoader = api.getClass().getMethod("getShopLoader");
                    Object shopLoader = getShopLoader.invoke(api);
                    if (shopLoader != null) {
                        shopLoader.getClass().getMethod("loadShops").invoke(shopLoader);
                        plugin.getLogger().info("[quickshop] Reloaded QuickShop shops from database after claim.");
                    }
                } catch (Exception reloadException) {
                    plugin.getLogger().warning("[quickshop] Post-reload shop refresh failed: " + reloadException.getMessage());
                }
            } catch (NoSuchMethodException ignored) {
                try {
                    Method reloadModule = shopManager.getClass().getMethod("reloadModule");
                    Object reloadResult = reloadModule.invoke(shopManager);
                    plugin.getLogger().info("[quickshop] Reloaded QuickShop shop manager after claim: " + String.valueOf(reloadResult));
                } catch (NoSuchMethodException secondaryIgnored) {
                    plugin.getLogger().warning("[quickshop] QuickShop runtime reload API is unavailable; skipped full runtime reload.");
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private boolean refreshRuntimeShopObject(
            ClaimContext context,
            Object shop,
            Object newOwner,
            Method setOwner,
            Method getPermissionAudiences,
            Method setPlayerGroup,
            Method setSignText,
            Method setDirty,
            Method updateSync
    ) throws Exception {
        if (shop == null) {
            return false;
        }

        setOwner.invoke(shop, newOwner);

        Map<UUID, String> permissions = (Map<UUID, String>) getPermissionAudiences.invoke(shop);
        String targetGroup = permissions.get(context.newUuid());
        String legacyGroup = permissions.remove(context.legacyUuid());
        String holderGroup = permissions.remove(context.config().quickshopHolderUuid());
        if (targetGroup == null) {
            targetGroup = legacyGroup != null ? legacyGroup : holderGroup;
        }
        if (targetGroup != null) {
            permissions.put(context.newUuid(), targetGroup);
            setPlayerGroup.invoke(shop, context.newUuid(), targetGroup);
        }

        setDirty.invoke(shop, true);
        setSignText.invoke(shop);
        updateSync.invoke(shop);
        return true;
    }

    private String connectionSource(Connection connection) {
        if (connection == null) {
            return "unknown";
        }
        String className = connection.getClass().getName();
        return className.contains("Hikari") ? "quickshop-managed" : "direct-h2";
    }

    private String stripDatabaseSuffix(String path) {
        if (path.endsWith(".mv.db")) {
            return path.substring(0, path.length() - ".mv.db".length());
        }
        if (path.endsWith(".h2.db")) {
            return path.substring(0, path.length() - ".h2.db".length());
        }
        return path;
    }

    private Path snapshotDatabasePath(ScanContext context) {
        return context.snapshotRoot().resolve("plugins/QuickShop-Hikari/shops.mv.db");
    }

    private Path liveDatabasePath(Path serverRoot) {
        return serverRoot.resolve("plugins/QuickShop-Hikari/shops.mv.db");
    }

    private UUID parseUuid(String rawUuid) {
        if (rawUuid == null || rawUuid.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(rawUuid.trim());
            return NIL_UUID.equals(uuid) ? null : uuid;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String buildInClause(String columnName, int size) {
        return columnName + " IN (" + String.join(", ", java.util.Collections.nCopies(size, "?")) + ")";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String replaceUuidReferences(String value, List<String> sourceUuids, String targetUuid) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String replaced = value;
        for (String sourceUuid : sourceUuids) {
            if (sourceUuid == null || sourceUuid.isBlank()) {
                continue;
            }
            replaced = replaced.replace(sourceUuid, targetUuid);
        }
        return replaced;
    }

    private record QuickShopAsset(long shopId) {
    }

    private record QuickShopBinding(UUID legacyUuid, long shopId) {
    }

    private record QuickShopTables(String dataTable, String playersTable) {
    }

    private record Snapshot(
            QuickShopTables tables,
            JdbcTableUtil.TableSnapshot dataSnapshot,
            JdbcTableUtil.TableSnapshot playerSnapshot,
            List<Long> shopIds,
            List<String> playerUuids
    ) {
    }

    private record StoredSnapshot(
            QuickShopTables tables,
            JdbcSnapshotStore.StoredTableSnapshot dataSnapshot,
            JdbcSnapshotStore.StoredTableSnapshot playerSnapshot,
            List<Long> shopIds,
            List<String> playerUuids
    ) {
    }

    private record PlayerProfile(String uuid, String locale, String cachedName) {
    }
}
