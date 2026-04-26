package DE.MrSvenSF.mrsvensf.sync;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class SyncItemsListener implements Listener {

    private final JavaPlugin plugin;
    private final SyncItemsService syncItemsService;

    public SyncItemsListener(JavaPlugin plugin, SyncItemsService syncItemsService) {
        this.plugin = plugin;
        this.syncItemsService = syncItemsService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!syncItemsService.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        SyncItemsService.PlayerSyncData data = syncItemsService.capturePlayerData(player);
        if (data == null || data.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> syncItemsService.savePlayerData(uuid, data));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!syncItemsService.isEnabled()) {
            return;
        }

        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SyncItemsService.PlayerSyncData data = syncItemsService.loadPlayerData(uuid);
            if (data == null || data.isEmpty()) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player onlinePlayer = Bukkit.getPlayer(uuid);
                if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                    return;
                }
                syncItemsService.applyPlayerData(onlinePlayer, data);
            });
        });
    }
}
