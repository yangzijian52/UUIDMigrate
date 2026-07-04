package cn.uuidmigrate.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Callable;

public final class BukkitSyncUtil {
    private BukkitSyncUtil() {
    }

    public static void run(JavaPlugin plugin, ThrowingRunnable runnable) throws Exception {
        call(plugin, () -> {
            runnable.run();
            return null;
        });
    }

    public static <T> T call(JavaPlugin plugin, Callable<T> callable) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return callable.call();
        }
        return Bukkit.getScheduler().callSyncMethod(plugin, callable).get();
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
