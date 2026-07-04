package cn.uuidmigrate.listener;

import cn.uuidmigrate.service.PendingClaimService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PendingClaimListener implements Listener {
    private final PendingClaimService pendingClaimService;

    public PendingClaimListener(PendingClaimService pendingClaimService) {
        this.pendingClaimService = pendingClaimService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        if (!pendingClaimService.hasPending(event.getPlayer().getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String password = PlainTextComponentSerializer.plainText().serialize(event.message());
        pendingClaimService.consumePassword(event.getPlayer().getUniqueId(), password);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingClaimService.clearForQuit(event.getPlayer().getUniqueId());
    }
}
