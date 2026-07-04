package cn.uuidmigrate.service;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.config.ConfigService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PendingClaimService {
    private static final String AUTH_FAILURE_MESSAGE = "&c旧账号不存在或密码错误";

    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final ClaimService claimService;
    private final AuthMePasswordVerifier authMePasswordVerifier;
    private final Map<UUID, PendingClaim> pendingClaims = new ConcurrentHashMap<>();

    public PendingClaimService(
            UUIDMigratePlugin plugin,
            ConfigService configService,
            ClaimService claimService,
            AuthMePasswordVerifier authMePasswordVerifier
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.claimService = claimService;
        this.authMePasswordVerifier = authMePasswordVerifier;
    }

    public void beginPendingClaim(Player player, String legacyName) {
        UUID playerUuid = player.getUniqueId();
        PendingClaim existing = pendingClaims.get(playerUuid);
        if (existing != null && !existing.isExpired()) {
            player.sendMessage(color("&e你已经有一个旧账号认领正在等待密码验证。"));
            player.sendMessage(color("&7请直接在聊天框输入旧 AuthMe 密码，或等待当前验证超时后再试。"));
            return;
        }
        removePending(playerUuid, false);

        int timeoutSeconds = Math.max(10, configService.config().pendingClaimTimeoutSeconds());
        PendingClaim pendingClaim = new PendingClaim(
                playerUuid,
                legacyName,
                Instant.now().plusSeconds(timeoutSeconds)
        );
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> expirePendingClaim(playerUuid, pendingClaim), timeoutSeconds * 20L);
        pendingClaim.setTimeoutTask(timeoutTask);
        pendingClaims.put(playerUuid, pendingClaim);

        player.sendMessage(color("&a已开始验证旧账号: &f" + legacyName));
        player.sendMessage(color("&e请在 &f" + timeoutSeconds + " &e秒内直接发送旧 AuthMe 密码。"));
        player.sendMessage(color("&7这条聊天会被插件拦截，不会进入公屏。"));
    }

    public boolean hasPending(UUID playerUuid) {
        PendingClaim pendingClaim = pendingClaims.get(playerUuid);
        if (pendingClaim == null) {
            return false;
        }
        if (pendingClaim.isExpired()) {
            if (pendingClaims.remove(playerUuid, pendingClaim)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingClaim.cancelTimeoutTask();
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(color("&e旧账号密码验证已超时，请重新执行 &f/uuidmigrate claim <离线服老名字>&e。"));
                    }
                });
            }
            return false;
        }
        return true;
    }

    public void consumePassword(UUID playerUuid, String password) {
        PendingClaim pendingClaim = pendingClaims.get(playerUuid);
        if (pendingClaim == null) {
            return;
        }
        if (pendingClaim.isExpired()) {
            removePending(playerUuid, true);
            return;
        }
        if (!pendingClaim.passwordConsumed.compareAndSet(false, true)) {
            runSyncMessage(playerUuid, "&e旧账号密码正在验证中，请稍候。");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, pendingClaim::cancelTimeoutTask);
        runSyncMessage(playerUuid, "&7正在验证旧账号密码...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> verifyPasswordAsync(pendingClaim, password));
    }

    public void clearForQuit(UUID playerUuid) {
        removePending(playerUuid, false);
    }

    public void cancelAll() {
        pendingClaims.values().forEach(PendingClaim::cancelTimeoutTask);
        pendingClaims.clear();
    }

    private void verifyPasswordAsync(PendingClaim pendingClaim, String password) {
        boolean verified = false;
        try {
            verified = authMePasswordVerifier.verifySha256Password(pendingClaim.legacyName, password);
        } catch (Exception exception) {
            plugin.getLogger().warning("AuthMe password verification backend failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }

        boolean finalVerified = verified;
        Bukkit.getScheduler().runTask(plugin, () -> finishVerification(pendingClaim, finalVerified));
    }

    private void finishVerification(PendingClaim pendingClaim, boolean verified) {
        if (!pendingClaims.remove(pendingClaim.playerUuid, pendingClaim)) {
            return;
        }

        Player player = Bukkit.getPlayer(pendingClaim.playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!verified) {
            player.sendMessage(color(AUTH_FAILURE_MESSAGE));
            return;
        }

        try {
            claimService.startClaim(player, pendingClaim.legacyName);
        } catch (Exception exception) {
            player.sendMessage(color("&c开始认领失败: " + exception.getMessage()));
            plugin.getLogger().warning("AuthMe-verified claim start failed for player " + pendingClaim.playerUuid + " and legacy name " + pendingClaim.legacyName + ": " + exception.getMessage());
        }
    }

    private void expirePendingClaim(UUID playerUuid, PendingClaim pendingClaim) {
        if (pendingClaim.passwordConsumed.get()) {
            return;
        }
        if (!pendingClaims.remove(playerUuid, pendingClaim)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(color("&e旧账号密码验证已超时，请重新执行 &f/uuidmigrate claim <离线服老名字>&e。"));
        }
    }

    private void removePending(UUID playerUuid, boolean notify) {
        PendingClaim removed = pendingClaims.remove(playerUuid);
        if (removed == null) {
            return;
        }
        removed.cancelTimeoutTask();
        if (notify) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(color("&e旧账号密码验证已超时，请重新执行 &f/uuidmigrate claim <离线服老名字>&e。"));
            }
        }
    }

    private void runSyncMessage(UUID playerUuid, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(color(message));
            }
        });
    }

    private String color(String message) {
        return message.replace('&', '\u00A7');
    }

    private static final class PendingClaim {
        private final UUID playerUuid;
        private final String legacyName;
        private final Instant expiresAt;
        private final AtomicBoolean passwordConsumed = new AtomicBoolean(false);
        private BukkitTask timeoutTask;

        private PendingClaim(UUID playerUuid, String legacyName, Instant expiresAt) {
            this.playerUuid = playerUuid;
            this.legacyName = legacyName;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        private void setTimeoutTask(BukkitTask timeoutTask) {
            this.timeoutTask = timeoutTask;
        }

        private void cancelTimeoutTask() {
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }
        }
    }
}
