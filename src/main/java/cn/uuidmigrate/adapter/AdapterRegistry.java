package cn.uuidmigrate.adapter;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.adapter.impl.EssentialsAdapter;
import cn.uuidmigrate.adapter.impl.FakePlayerAdapter;
import cn.uuidmigrate.adapter.impl.GenericSqliteUuidNameAdapter;
import cn.uuidmigrate.adapter.impl.LuckPermsAdapter;
import cn.uuidmigrate.adapter.impl.QuickShopHikariAdapter;
import cn.uuidmigrate.adapter.impl.ResidenceAdapter;
import cn.uuidmigrate.adapter.impl.SimplePlaytimeAdapter;
import cn.uuidmigrate.adapter.impl.VanillaAdapter;
import cn.uuidmigrate.adapter.impl.XConomyAdapter;
import cn.uuidmigrate.adapter.impl.XyKitAdapter;
import cn.uuidmigrate.config.ConfigService;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AdapterRegistry {
    private final List<MigrationAdapter> adapters;

    private AdapterRegistry(List<MigrationAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public static AdapterRegistry createDefault(UUIDMigratePlugin plugin, ConfigService configService) {
        return new AdapterRegistry(List.of(
                new VanillaAdapter(plugin, configService),
                new EssentialsAdapter(plugin, configService),
                new XConomyAdapter(plugin, configService),
                new LuckPermsAdapter(plugin, configService),
                new ResidenceAdapter(plugin, configService),
                new QuickShopHikariAdapter(plugin, configService),
                new GenericSqliteUuidNameAdapter(
                        plugin,
                        configService,
                        "playertitle",
                        "PlayerTitle database",
                        "plugins/PlayerTitle/PlayerTitle.db",
                        List.of("player_uuid"),
                        List.of("player_name")
                ),
                new GenericSqliteUuidNameAdapter(
                        plugin,
                        configService,
                        "playertask",
                        "PlayerTask database",
                        "plugins/PlayerTask/PlayerTask.db",
                        List.of("player_uuid"),
                        List.of("player_name")
                ),
                new GenericSqliteUuidNameAdapter(
                        plugin,
                        configService,
                        "litesignin",
                        "LiteSignIn database",
                        "plugins/LiteSignIn/Database.db",
                        List.of("UUID"),
                        List.of("Name")
                ),
                new XyKitAdapter(plugin, configService),
                new SimplePlaytimeAdapter(plugin, configService),
                new GenericSqliteUuidNameAdapter(
                        plugin,
                        configService,
                        "holomobhealth",
                        "HoloMobHealth database",
                        "plugins/HoloMobHealth/database.db",
                        List.of("UUID"),
                        List.of("NAME")
                ),
                new FakePlayerAdapter(plugin, configService)
        ));
    }

    public List<MigrationAdapter> adapters() {
        return adapters;
    }

    public Optional<MigrationAdapter> findAdapter(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return adapters.stream()
                .filter(adapter -> adapter.key().equalsIgnoreCase(normalized))
                .findFirst();
    }
}
