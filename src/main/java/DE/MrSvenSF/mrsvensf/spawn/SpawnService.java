package DE.MrSvenSF.mrsvensf.spawn;

import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class SpawnService {

    private static final long INVALID_WORLD_WARNING_COOLDOWN_MS = 60_000L;

    private final JavaPlugin plugin;
    private final ConfigSystem configSystem;
    private long lastInvalidWorldWarningMs;

    public SpawnService(JavaPlugin plugin, ConfigSystem configSystem) {
        this.plugin = plugin;
        this.configSystem = configSystem;
    }

    public boolean isSpawnOnJoinEnabled() {
        FileConfiguration spawnConfig = configSystem.getSpawnConfig();
        return isSpawnFeatureEnabled() && getBoolean(spawnConfig, true, "onjoin", "spawn.onjoin");
    }

    public boolean isSpawnFeatureEnabled() {
        if (!configSystem.isSpawnEnabled()) {
            return false;
        }

        FileConfiguration spawnConfig = configSystem.getSpawnConfig();
        return getBoolean(spawnConfig, true, "enabled", "spawn.enabled");
    }

    public Optional<Location> getSpawnLocation() {
        if (!isSpawnFeatureEnabled()) {
            return Optional.empty();
        }

        FileConfiguration spawnConfig = configSystem.getSpawnConfig();
        String worldName = spawnConfig.getString("spawn.world", "");
        if (worldName == null || worldName.isBlank()) {
            return Optional.empty();
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            warnInvalidWorld(worldName);
            return Optional.empty();
        }

        if (!spawnConfig.contains("spawn.x") || !spawnConfig.contains("spawn.y") || !spawnConfig.contains("spawn.z")) {
            return Optional.empty();
        }

        double x = spawnConfig.getDouble("spawn.x");
        double y = spawnConfig.getDouble("spawn.y");
        double z = spawnConfig.getDouble("spawn.z");
        float yaw = (float) spawnConfig.getDouble("spawn.yaw", 0.0d);
        float pitch = (float) spawnConfig.getDouble("spawn.pitch", 0.0d);

        return Optional.of(new Location(world, x, y, z, yaw, pitch));
    }

    public boolean teleportToSpawn(Player player) {
        Optional<Location> spawn = getSpawnLocation();
        if (spawn.isEmpty()) {
            return false;
        }

        player.teleport(spawn.get());
        return true;
    }

    public void setSpawn(Location location) {
        FileConfiguration spawnConfig = configSystem.getSpawnConfig();
        boolean featureEnabled = getBoolean(spawnConfig, true, "enabled", "spawn.enabled");
        boolean spawnOnJoinEnabled = getBoolean(spawnConfig, true, "onjoin", "spawn.onjoin");

        spawnConfig.set("enabled", featureEnabled);
        spawnConfig.set("onjoin", spawnOnJoinEnabled);
        spawnConfig.set("spawn.world", location.getWorld() == null ? "world" : location.getWorld().getName());
        spawnConfig.set("spawn.x", location.getX());
        spawnConfig.set("spawn.y", location.getY());
        spawnConfig.set("spawn.z", location.getZ());
        spawnConfig.set("spawn.yaw", location.getYaw());
        spawnConfig.set("spawn.pitch", location.getPitch());
        configSystem.saveSpawnConfig();
    }

    private void warnInvalidWorld(String worldName) {
        long now = System.currentTimeMillis();
        if (now - lastInvalidWorldWarningMs < INVALID_WORLD_WARNING_COOLDOWN_MS) {
            return;
        }
        lastInvalidWorldWarningMs = now;
        plugin.getLogger().warning("Spawn-Welt ist nicht geladen oder existiert nicht: " + worldName);
    }

    private boolean getBoolean(FileConfiguration config, boolean defaultValue, String... paths) {
        for (String path : paths) {
            Object value = getValueIgnoreCase(config, path);
            if (value != null) {
                return parseBoolean(value, defaultValue);
            }
        }
        return defaultValue;
    }

    private Object getValueIgnoreCase(FileConfiguration config, String path) {
        if (config == null || path == null || path.isBlank()) {
            return null;
        }

        String[] parts = path.split("\\.");
        ConfigurationSection current = config;
        for (int index = 0; index < parts.length; index++) {
            String matchingKey = findKeyIgnoreCase(current, parts[index]);
            if (matchingKey == null) {
                return null;
            }
            if (index == parts.length - 1) {
                return current.get(matchingKey);
            }
            current = current.getConfigurationSection(matchingKey);
            if (current == null) {
                return null;
            }
        }
        return null;
    }

    private String findKeyIgnoreCase(ConfigurationSection section, String key) {
        if (section == null || key == null || key.isBlank()) {
            return null;
        }
        for (String existingKey : section.getKeys(false)) {
            if (existingKey.equalsIgnoreCase(key)) {
                return existingKey;
            }
        }
        return null;
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        if (value instanceof String stringValue) {
            String normalized = stringValue.trim().toLowerCase();
            if (normalized.equals("true") || normalized.equals("yes") || normalized.equals("1")) {
                return true;
            }
            if (normalized.equals("false") || normalized.equals("no") || normalized.equals("0")) {
                return false;
            }
        }
        return defaultValue;
    }
}
