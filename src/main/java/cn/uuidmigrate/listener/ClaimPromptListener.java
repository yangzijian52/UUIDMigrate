package cn.uuidmigrate.listener;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.config.PluginMode;
import cn.uuidmigrate.db.IndexDatabase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ClaimPromptListener implements Listener {
    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final IndexDatabase indexDatabase;

    public ClaimPromptListener(UUIDMigratePlugin plugin, ConfigService configService, IndexDatabase indexDatabase) {
        this.plugin = plugin;
        this.configService = configService;
        this.indexDatabase = indexDatabase;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (configService.config().mode() != PluginMode.CLAIM) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> findPromptCandidate(playerUuid, playerName));
    }

    private void findPromptCandidate(UUID playerUuid, String playerName) {
        try {
            Optional<String> latestSnapshotId = indexDatabase.latestSnapshotId();
            if (latestSnapshotId.isEmpty() || !latestSnapshotId.get().equals(configService.config().snapshotId())) {
                return;
            }

            Optional<String> latestScanId = indexDatabase.latestScanId();
            if (latestScanId.isEmpty()) {
                return;
            }

            if (indexDatabase.findClaimedByNewUuid(playerUuid).isPresent()) {
                return;
            }

            Optional<IndexDatabase.ClaimPromptCandidateRow> candidate = indexDatabase.findClaimPromptCandidate(
                    latestScanId.get(),
                    playerName,
                    ignoredLegacyUuids(),
                    configService.config().skipFloodgatePlayers()
            );
            candidate.ifPresent(row -> Bukkit.getScheduler().runTask(plugin, () -> sendPromptIfOnline(playerUuid, row)));
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to evaluate claim prompt for " + playerName + ": " + exception.getMessage());
        }
    }

    private void sendPromptIfOnline(UUID playerUuid, IndexDatabase.ClaimPromptCandidateRow candidate) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline() || configService.config().mode() != PluginMode.CLAIM) {
            return;
        }

        String command = "/uuidmigrate claim " + candidate.legacyName();
        Component message = Component.text("检测到您未进行数据转移，请点击", NamedTextColor.YELLOW)
                .append(Component.text("这里", NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY))))
                .append(Component.text("进行转移。", NamedTextColor.YELLOW));
        player.sendMessage(message);
    }

    private Set<UUID> ignoredLegacyUuids() {
        return Set.of(
                configService.config().residenceHolderUuid(),
                configService.config().quickshopHolderUuid()
        );
    }
}
