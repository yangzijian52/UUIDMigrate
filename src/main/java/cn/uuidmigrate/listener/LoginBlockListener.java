package cn.uuidmigrate.listener;

import cn.uuidmigrate.service.LoginBlockService;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class LoginBlockListener implements Listener {
    private final LoginBlockService loginBlockService;

    public LoginBlockListener(LoginBlockService loginBlockService) {
        this.loginBlockService = loginBlockService;
    }

    @EventHandler
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!loginBlockService.isBlocked(event.getUniqueId())) {
            return;
        }

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(loginBlockService.message(event.getUniqueId())));
    }
}
