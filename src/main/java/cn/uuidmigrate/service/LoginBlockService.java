package cn.uuidmigrate.service;

import cn.uuidmigrate.db.IndexDatabase;
import io.papermc.paper.ban.BanListType;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginBlockService {
    private final JavaPlugin plugin;
    private final IndexDatabase indexDatabase;
    private final Map<UUID, BlockRecord> blocks = new ConcurrentHashMap<>();

    public LoginBlockService(JavaPlugin plugin, IndexDatabase indexDatabase) {
        this.plugin = plugin;
        this.indexDatabase = indexDatabase;
    }

    public void block(UUID uuid, String playerName, Instant until, String message, String reason) {
        blocks.put(uuid, new BlockRecord(until, message, reason, playerName));
        applyTemporaryBan(uuid, playerName, until, reason);
    }

    public void unblock(UUID uuid, String playerName) {
        blocks.remove(uuid);
        pardon(uuid, playerName);
    }

    public boolean isBlocked(UUID uuid) {
        BlockRecord record = blocks.get(uuid);
        if (record != null) {
            if (record.until() != null && Instant.now().isAfter(record.until())) {
                blocks.remove(uuid, record);
            } else {
                return true;
            }
        }

        try {
            return indexDatabase.hasRunningClaimForNewUuid(uuid);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to inspect running claim state for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    public String message(UUID uuid) {
        BlockRecord record = blocks.get(uuid);
        return record == null ? "Migration in progress. Please contact an admin if this does not clear soon." : record.message();
    }

    private ProfileBanList profileBanList() {
        return Bukkit.getBanList(BanListType.PROFILE);
    }

    private void applyTemporaryBan(UUID uuid, String playerName, Instant until, String reason) {
        try {
            runBanOperation(() -> profileBanList().addBan(profile(uuid, playerName), normalizedReason(reason), until, plugin.getName()));
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to apply temporary profile ban for " + uuid + ": " + exception.getMessage());
        }
    }

    private void pardon(UUID uuid, String playerName) {
        try {
            runBanOperation(() -> profileBanList().pardon(profile(uuid, playerName)));
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to pardon temporary profile ban for " + uuid + ": " + exception.getMessage());
        }
    }

    private void runBanOperation(ThrowingRunnable action) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            action.run();
            return null;
        }).get();
    }

    private PlayerProfile profile(UUID uuid, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Bukkit.createPlayerProfile(uuid);
        }
        return Bukkit.createPlayerProfile(uuid, playerName);
    }

    private String normalizedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UUID migration in progress";
        }
        return reason;
    }

    public record BlockRecord(Instant until, String message, String reason, String playerName) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
