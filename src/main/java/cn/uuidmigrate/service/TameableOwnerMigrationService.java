package cn.uuidmigrate.service;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.util.BukkitSyncUtil;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TameableOwnerMigrationService implements Listener {
    private final UUIDMigratePlugin plugin;
    private final Map<UUID, OwnerBinding> ownerBindings = new ConcurrentHashMap<>();

    public TameableOwnerMigrationService(UUIDMigratePlugin plugin, IndexDatabase indexDatabase) throws Exception {
        this.plugin = plugin;
        for (IndexDatabase.ClaimedUuidBindingRow row : indexDatabase.loadClaimedUuidBindings().values()) {
            ownerBindings.put(row.legacyUuid(), new OwnerBinding(row.newUuid(), row.newName()));
        }
    }

    public void afterClaimSucceeded(ClaimContext context) throws Exception {
        ownerBindings.put(context.legacyUuid(), new OwnerBinding(context.newUuid(), context.newName()));
        int updated = BukkitSyncUtil.call(plugin, this::migrateLoadedEntities);
        if (updated > 0) {
            plugin.getLogger().info("[tameable] Updated " + updated + " loaded tameable owner(s): "
                    + context.legacyUuid() + " -> " + context.newUuid());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (ownerBindings.isEmpty()) {
            return;
        }

        int updated = migrateChunk(event.getChunk());
        if (updated > 0) {
            plugin.getLogger().info("[tameable] Updated " + updated + " tameable owner(s) in loaded chunk "
                    + event.getWorld().getName() + " " + event.getChunk().getX() + "," + event.getChunk().getZ());
        }
    }

    private int migrateLoadedEntities() {
        int updated = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (migrateEntity(entity)) {
                    updated++;
                }
            }
        }
        return updated;
    }

    private int migrateChunk(Chunk chunk) {
        int updated = 0;
        for (Entity entity : chunk.getEntities()) {
            if (migrateEntity(entity)) {
                updated++;
            }
        }
        return updated;
    }

    private boolean migrateEntity(Entity entity) {
        if (!(entity instanceof Tameable tameable)) {
            return false;
        }

        AnimalTamer owner = tameable.getOwner();
        if (owner == null) {
            return false;
        }

        OwnerBinding binding = ownerBindings.get(owner.getUniqueId());
        if (binding == null) {
            return false;
        }

        tameable.setOwner(plugin.getServer().getOfflinePlayer(binding.newUuid()));
        return true;
    }

    private record OwnerBinding(UUID newUuid, String newName) {
    }
}
