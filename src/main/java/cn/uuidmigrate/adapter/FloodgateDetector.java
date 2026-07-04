package cn.uuidmigrate.adapter;

import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

final class FloodgateDetector {
    private static final String FLOODGATE_UUID_PREFIX = "00000000-0000-0000-0009-";

    private final JavaPlugin plugin;
    private final Method getInstanceMethod;
    private final Method isFloodgatePlayerMethod;
    private volatile boolean reflectionFailureLogged;

    FloodgateDetector(JavaPlugin plugin) {
        this.plugin = plugin;

        Method resolvedGetInstance = null;
        Method resolvedIsFloodgatePlayer = null;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            resolvedGetInstance = apiClass.getMethod("getInstance");
            resolvedIsFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
        } catch (ReflectiveOperationException ignored) {
        }

        this.getInstanceMethod = resolvedGetInstance;
        this.isFloodgatePlayerMethod = resolvedIsFloodgatePlayer;
    }

    boolean isFloodgatePlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (uuid.toString().regionMatches(true, 0, FLOODGATE_UUID_PREFIX, 0, FLOODGATE_UUID_PREFIX.length())) {
            return true;
        }
        if (getInstanceMethod == null || isFloodgatePlayerMethod == null) {
            return false;
        }

        try {
            Object api = getInstanceMethod.invoke(null);
            if (api == null) {
                return false;
            }
            Object result = isFloodgatePlayerMethod.invoke(api, uuid);
            return result instanceof Boolean booleanResult && booleanResult;
        } catch (ReflectiveOperationException exception) {
            if (!reflectionFailureLogged) {
                reflectionFailureLogged = true;
                plugin.getLogger().warning("Floodgate API detection failed, falling back to UUID/prefix heuristics: " + exception.getMessage());
            }
            return false;
        }
    }
}
