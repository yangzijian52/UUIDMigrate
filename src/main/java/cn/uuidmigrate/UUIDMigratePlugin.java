package cn.uuidmigrate;

import cn.uuidmigrate.adapter.AdapterRegistry;
import cn.uuidmigrate.command.UuidMigrateCommand;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.listener.ClaimPromptListener;
import cn.uuidmigrate.listener.LoginBlockListener;
import cn.uuidmigrate.listener.PendingClaimListener;
import cn.uuidmigrate.service.AuthMePasswordVerifier;
import cn.uuidmigrate.service.ClaimService;
import cn.uuidmigrate.service.ClaimRuntimeStateService;
import cn.uuidmigrate.service.LoginBlockService;
import cn.uuidmigrate.service.PendingClaimService;
import cn.uuidmigrate.service.PrepareService;
import cn.uuidmigrate.service.ReportService;
import cn.uuidmigrate.service.RollbackService;
import cn.uuidmigrate.service.ScanService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class UUIDMigratePlugin extends JavaPlugin {
    private ConfigService configService;
    private IndexDatabase indexDatabase;
    private AdapterRegistry adapterRegistry;
    private ScanService scanService;
    private ReportService reportService;
    private LoginBlockService loginBlockService;
    private ClaimRuntimeStateService claimRuntimeStateService;
    private PrepareService prepareService;
    private ClaimService claimService;
    private RollbackService rollbackService;
    private AuthMePasswordVerifier authMePasswordVerifier;
    private PendingClaimService pendingClaimService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            this.configService = new ConfigService(this);
            this.configService.reloadAndValidate();

            this.indexDatabase = new IndexDatabase(this);
            this.indexDatabase.initialize();

            this.adapterRegistry = AdapterRegistry.createDefault(this, configService);
            this.loginBlockService = new LoginBlockService(this, indexDatabase);
            this.claimRuntimeStateService = new ClaimRuntimeStateService();
            this.scanService = new ScanService(this, configService, indexDatabase, adapterRegistry);
            this.reportService = new ReportService(this, configService, indexDatabase, adapterRegistry);
            this.prepareService = new PrepareService(this, configService, indexDatabase, adapterRegistry);
            this.rollbackService = new RollbackService(this, configService, indexDatabase, adapterRegistry, loginBlockService, claimRuntimeStateService);
            this.claimService = new ClaimService(this, configService, indexDatabase, adapterRegistry, loginBlockService, claimRuntimeStateService, rollbackService);
            this.authMePasswordVerifier = new AuthMePasswordVerifier(configService);
            this.pendingClaimService = new PendingClaimService(this, configService, claimService, authMePasswordVerifier);

            UuidMigrateCommand commandHandler = new UuidMigrateCommand(this, configService, indexDatabase, scanService, reportService, prepareService, claimService, rollbackService, claimRuntimeStateService, pendingClaimService);
            PluginCommand command = Objects.requireNonNull(getCommand("uuidmigrate"), "uuidmigrate command missing from plugin.yml");
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);

            getServer().getPluginManager().registerEvents(new LoginBlockListener(loginBlockService), this);
            getServer().getPluginManager().registerEvents(new PendingClaimListener(pendingClaimService), this);
            getServer().getPluginManager().registerEvents(new ClaimPromptListener(this, configService, indexDatabase), this);

            var recoveredClaimIds = rollbackService.recoverInterruptedClaimsOnStartup();
            if (!recoveredClaimIds.isEmpty()) {
                getLogger().warning("Recovered interrupted claims on startup: " + String.join(", ", recoveredClaimIds));
            }

            getLogger().info("UUIDMigrate enabled. Mode=" + configService.config().mode() + ", snapshot=" + configService.config().snapshotId());
        } catch (Exception exception) {
            getLogger().severe("Failed to enable UUIDMigrate: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (pendingClaimService != null) {
            pendingClaimService.cancelAll();
        }
        if (indexDatabase != null) {
            indexDatabase.close();
        }
    }
}
