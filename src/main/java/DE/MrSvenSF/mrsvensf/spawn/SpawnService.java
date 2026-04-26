package DE.MrSvenSF.mrsvensf.spawn;

import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class SpawnService {

    private final ConfigSystem configSystem;

    public SpawnService(ConfigSystem configSystem) {
        this.configSystem = configSystem;
    }

    public boolean isSpawnOnJoinEnabled() {
        FileConfiguration spawnConfig = configSystem.getSpawnConfig();
        return isSpawnFeatureEnabled() && getSpawnBoolean(spawnConfig, "onjoin", true);
    }

    public boolean isSpawnFeatureEnabled() {
        if (!configSystem.isSpawnEnabled()) {
            return false;
        }

        FileConfiguration spawnConfig = configSystem.getSpawnConfig();
        return getSpawnBoolean(spawnConfig, "on", true);
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
        boolean onJoin = isSpawnOnJoinEnabled();

        spawnConfig.set("spawn.onjoin", onJoin);
        spawnConfig.set("spawn.world", location.getWorld() == null ? "world" : location.getWorld().getName());
        spawnConfig.set("spawn.x", location.getX());
        spawnConfig.set("spawn.y", location.getY());
        spawnConfig.set("spawn.z", location.getZ());
        spawnConfig.set("spawn.yaw", location.getYaw());
        spawnConfig.set("spawn.pitch", location.getPitch());
        configSystem.saveSpawnConfig();
    }

    private boolean getSpawnBoolean(FileConfiguration config, String key, boolean defaultValue) {
        ConfigurationSection section = config.getConfigurationSection("spawn");
        if (section == null) {
            return defaultValue;
        }

        for (String existingKey : section.getKeys(false)) {
            if (!existingKey.equalsIgnoreCase(key)) {
                continue;
            }

            Object value = section.get(existingKey);
            if (value instanceof Boolean boolValue) {
                return boolValue;
            }
            if (value instanceof Number numberValue) {
                return numberValue.intValue() != 0;
            }
            if (value instanceof String stringValue) {
                String normalized = stringValue.trim().toLowerCase();
                if (normalized.equals("true") || normalized.equals("yes") || normalized.equals("on") || normalized.equals("1")) {
                    return true;
                }
                if (normalized.equals("false") || normalized.equals("no") || normalized.equals("off") || normalized.equals("0")) {
                    return false;
                }
            }
        }

        return defaultValue;
    }
}
