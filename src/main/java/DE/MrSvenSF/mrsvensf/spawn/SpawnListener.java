package DE.MrSvenSF.mrsvensf.spawn;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpawnListener implements Listener {

    private final JavaPlugin plugin;
    private final SpawnService spawnService;

    public SpawnListener(JavaPlugin plugin, SpawnService spawnService) {
        this.plugin = plugin;
        this.spawnService = spawnService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!spawnService.isSpawnOnJoinEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> spawnService.teleportToSpawn(event.getPlayer()));
    }
}
