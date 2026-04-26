package DE.MrSvenSF.mrsvensf.sync;

import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SyncItemsService {

    private static final long REMOTE_WARNING_COOLDOWN_MS = 60_000L;
    private static final long KEY_WARNING_COOLDOWN_MS = 60_000L;
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 60L;
    private static final String REMOTE_TABLE_NAME = "mrsvensf_sync_items";

    private final JavaPlugin plugin;
    private final ConfigSystem configSystem;
    private final Map<UUID, String> activeSyncKeyByPlayer = new ConcurrentHashMap<>();
    private volatile long lastRemoteWarningMs;
    private volatile long lastKeyWarningMs;
    private volatile boolean remoteSchemaChecked;
    private BukkitTask cleanupTask;

    public SyncItemsService(JavaPlugin plugin, ConfigSystem configSystem) {
        this.plugin = plugin;
        this.configSystem = configSystem;
    }

    public boolean isEnabled() {
        return configSystem.isSyncItemsEnabled() && hasAnyCategoryEnabled() && hasSyncKeysConfigured();
    }

    public void start() {
        stop();
        if (!isEnabled()) {
            return;
        }

        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::cleanupExpiredTransfers,
                CLEANUP_INTERVAL_TICKS,
                CLEANUP_INTERVAL_TICKS
        );
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        activeSyncKeyByPlayer.clear();
    }

    public void reloadSettings() {
        start();
    }

    public void persistOnlinePlayersNow() {
        if (!isEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSyncData data = capturePlayerData(player);
            if (data == null || data.isEmpty()) {
                continue;
            }
            savePlayerData(player.getUniqueId(), data);
        }
    }

    public PlayerSyncData capturePlayerData(Player player) {
        if (!isEnabled() || player == null) {
            return null;
        }

        ItemStack[] storage = null;
        ItemStack[] armor = null;
        ItemStack[] extra = null;
        ItemStack[] enderChest = null;
        ItemStack[] hotbar = null;

        if (configSystem.isSyncInventoryEnabled()) {
            PlayerInventory inventory = player.getInventory();
            storage = deepCopy(inventory.getStorageContents());
            armor = deepCopy(inventory.getArmorContents());
            extra = deepCopy(inventory.getExtraContents());
        }

        if (configSystem.isSyncEnderChestEnabled()) {
            enderChest = deepCopy(player.getEnderChest().getContents());
        }

        if (configSystem.isSyncHotbarEnabled()) {
            ItemStack[] source = player.getInventory().getStorageContents();
            hotbar = new ItemStack[9];
            for (int i = 0; i < hotbar.length && i < source.length; i++) {
                hotbar[i] = cloneItem(source[i]);
            }
        }

        PlayerSyncData data = new PlayerSyncData(storage, armor, extra, enderChest, hotbar);
        return data.isEmpty() ? null : data;
    }

    public void applyPlayerData(Player player, PlayerSyncData data) {
        if (!isEnabled() || player == null || data == null || data.isEmpty()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();

        if (configSystem.isSyncInventoryEnabled()) {
            if (data.storage() != null) {
                inventory.setStorageContents(adaptLength(data.storage(), inventory.getStorageContents().length));
            }
            if (data.armor() != null) {
                inventory.setArmorContents(adaptLength(data.armor(), inventory.getArmorContents().length));
            }
            if (data.extra() != null) {
                inventory.setExtraContents(adaptLength(data.extra(), inventory.getExtraContents().length));
            }
        }

        if (configSystem.isSyncHotbarEnabled() && data.hotbar() != null) {
            ItemStack[] storage = inventory.getStorageContents();
            ItemStack[] hotbar = adaptLength(data.hotbar(), 9);
            int max = Math.min(9, storage.length);
            for (int i = 0; i < max; i++) {
                storage[i] = cloneItem(hotbar[i]);
            }
            inventory.setStorageContents(storage);
        }

        if (configSystem.isSyncEnderChestEnabled() && data.enderChest() != null) {
            Inventory enderChest = player.getEnderChest();
            enderChest.setContents(adaptLength(data.enderChest(), enderChest.getSize()));
        }
    }

    public void savePlayerData(UUID uuid, PlayerSyncData data) {
        if (!isEnabled() || uuid == null || data == null || data.isEmpty()) {
            return;
        }

        List<String> allowedKeyHashes = resolveAllowedSyncKeyHashes();
        if (allowedKeyHashes.isEmpty()) {
            return;
        }

        String sourceServerId = resolveCurrentServerId();
        String selectedKeyHash = resolveSaveKeyHash(uuid, allowedKeyHashes);
        long expiresAt = calculateExpiresAt();

        if (isRemoteDatabaseUsable()) {
            try {
                saveToRemote(uuid, data, sourceServerId, selectedKeyHash, expiresAt);
                activeSyncKeyByPlayer.remove(uuid);
                return;
            } catch (Exception exception) {
                warnRemoteFailure("save", exception);
            }
        }

        saveToLocal(uuid, data, sourceServerId, selectedKeyHash, expiresAt);
        activeSyncKeyByPlayer.remove(uuid);
    }

    public PlayerSyncData loadPlayerData(UUID uuid) {
        if (!isEnabled() || uuid == null) {
            return null;
        }

        List<String> allowedKeyHashes = resolveAllowedSyncKeyHashes();
        if (allowedKeyHashes.isEmpty()) {
            return null;
        }

        String currentServerId = resolveCurrentServerId();

        if (isRemoteDatabaseUsable()) {
            try {
                LoadedTransfer transfer = loadFromRemote(uuid, currentServerId, allowedKeyHashes);
                if (transfer != null && transfer.data() != null && !transfer.data().isEmpty()) {
                    activeSyncKeyByPlayer.put(uuid, transfer.syncKeyHash());
                    return transfer.data();
                }
            } catch (Exception exception) {
                warnRemoteFailure("load", exception);
            }
        }

        LoadedTransfer localTransfer = loadFromLocal(uuid, currentServerId, allowedKeyHashes);
        if (localTransfer != null && localTransfer.data() != null && !localTransfer.data().isEmpty()) {
            activeSyncKeyByPlayer.put(uuid, localTransfer.syncKeyHash());
            return localTransfer.data();
        }

        activeSyncKeyByPlayer.remove(uuid);
        return null;
    }

    public void cleanupExpiredTransfers() {
        if (isRemoteDatabaseUsable()) {
            try {
                cleanupRemoteExpired();
            } catch (Exception exception) {
                warnRemoteFailure("cleanup", exception);
            }
        }
        cleanupLocalExpired();
    }

    private void saveToRemote(UUID uuid, PlayerSyncData data, String sourceServerId, String syncKeyHash, long expiresAt) throws Exception {
        try (Connection connection = openConnection()) {
            ensureTable(connection);

            String sql = "INSERT INTO `" + resolveSafeTableName() + "` " +
                    "(uuid, sync_key_hash, source_server, expires_at, storage_data, armor_data, extra_data, enderchest_data, hotbar_data, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "source_server = VALUES(source_server), " +
                    "expires_at = VALUES(expires_at), " +
                    "storage_data = VALUES(storage_data), " +
                    "armor_data = VALUES(armor_data), " +
                    "extra_data = VALUES(extra_data), " +
                    "enderchest_data = VALUES(enderchest_data), " +
                    "hotbar_data = VALUES(hotbar_data), " +
                    "updated_at = VALUES(updated_at)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, syncKeyHash);
                statement.setString(3, sourceServerId);
                statement.setLong(4, expiresAt);
                statement.setString(5, serializeItems(data.storage()));
                statement.setString(6, serializeItems(data.armor()));
                statement.setString(7, serializeItems(data.extra()));
                statement.setString(8, serializeItems(data.enderChest()));
                statement.setString(9, serializeItems(data.hotbar()));
                statement.setLong(10, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private LoadedTransfer loadFromRemote(UUID uuid, String currentServerId, List<String> allowedKeyHashes) throws Exception {
        if (allowedKeyHashes.isEmpty()) {
            return null;
        }

        try (Connection connection = openConnection()) {
            ensureTable(connection);

            String placeholders = String.join(",", Collections.nCopies(allowedKeyHashes.size(), "?"));
            String sql = "SELECT sync_key_hash, source_server, expires_at, storage_data, armor_data, extra_data, enderchest_data, hotbar_data, updated_at " +
                    "FROM `" + resolveSafeTableName() + "` " +
                    "WHERE uuid = ? AND sync_key_hash IN (" + placeholders + ") " +
                    "ORDER BY updated_at DESC";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                int parameterIndex = 2;
                for (String keyHash : allowedKeyHashes) {
                    statement.setString(parameterIndex++, keyHash);
                }

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String keyHash = resultSet.getString("sync_key_hash");
                        String sourceServer = normalizeServerId(resultSet.getString("source_server"));
                        long expiresAt = resultSet.getLong("expires_at");

                        if (isExpired(expiresAt)) {
                            deleteRemote(connection, uuid, keyHash);
                            continue;
                        }

                        if (sourceServer.equals(normalizeServerId(currentServerId))) {
                            continue;
                        }

                        PlayerSyncData data = new PlayerSyncData(
                                deserializeItems(resultSet.getString("storage_data")),
                                deserializeItems(resultSet.getString("armor_data")),
                                deserializeItems(resultSet.getString("extra_data")),
                                deserializeItems(resultSet.getString("enderchest_data")),
                                deserializeItems(resultSet.getString("hotbar_data"))
                        );

                        if (data.isEmpty()) {
                            deleteRemote(connection, uuid, keyHash);
                            continue;
                        }

                        deleteRemote(connection, uuid, keyHash);
                        return new LoadedTransfer(data, keyHash, null, resultSet.getLong("updated_at"));
                    }
                }
            }
        }

        return null;
    }

    private void saveToLocal(UUID uuid, PlayerSyncData data, String sourceServerId, String syncKeyHash, long expiresAt) {
        File targetFile = getLocalFile(uuid, syncKeyHash);
        YamlConfiguration local = new YamlConfiguration();
        local.set("uuid", uuid.toString());
        local.set("source-server", sourceServerId);
        local.set("sync-key-hash", syncKeyHash);
        local.set("updated-at", System.currentTimeMillis());
        local.set("expires-at", expiresAt);
        local.set("data.storage", serializeItems(data.storage()));
        local.set("data.armor", serializeItems(data.armor()));
        local.set("data.extra", serializeItems(data.extra()));
        local.set("data.enderchest", serializeItems(data.enderChest()));
        local.set("data.hotbar", serializeItems(data.hotbar()));
        try {
            local.save(targetFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Lokales Sync-Item-Speichern fehlgeschlagen: " + targetFile.getPath() + " | " + exception.getMessage());
        }
    }

    private LoadedTransfer loadFromLocal(UUID uuid, String currentServerId, List<String> allowedKeyHashes) {
        List<File> candidates = new ArrayList<>();
        for (String keyHash : allowedKeyHashes) {
            File keyedFile = getLocalFile(uuid, keyHash);
            if (keyedFile.exists()) {
                candidates.add(keyedFile);
            }
        }

        File legacyFile = getLegacyLocalFile(uuid);
        if (legacyFile.exists()) {
            candidates.add(legacyFile);
        }

        LoadedTransfer newest = null;
        for (File file : candidates) {
            LoadedTransfer candidate = readLocalTransfer(file, currentServerId, allowedKeyHashes);
            if (candidate == null) {
                continue;
            }
            if (newest == null || candidate.updatedAt() > newest.updatedAt()) {
                newest = candidate;
            }
        }

        if (newest == null) {
            return null;
        }

        if (newest.localFile() != null) {
            safeDelete(newest.localFile());
        }
        return newest;
    }

    private LoadedTransfer readLocalTransfer(File file, String currentServerId, List<String> allowedKeyHashes) {
        YamlConfiguration local = YamlConfiguration.loadConfiguration(file);

        String keyHash = local.getString("sync-key-hash", "").trim();
        if (keyHash.isBlank()) {
            keyHash = extractKeyHashFromFileName(file.getName());
        }
        if (keyHash.isBlank()) {
            if (allowedKeyHashes.size() == 1) {
                keyHash = allowedKeyHashes.get(0);
            } else {
                return null;
            }
        }
        if (!allowedKeyHashes.contains(keyHash)) {
            return null;
        }

        long expiresAt = local.getLong("expires-at", 0L);
        if (expiresAt <= 0L) {
            long updatedAtFallback = local.getLong("updated-at", 0L);
            if (updatedAtFallback > 0L) {
                expiresAt = updatedAtFallback + getExpireDurationMillis();
            }
        }
        if (isExpired(expiresAt)) {
            safeDelete(file);
            return null;
        }

        String sourceServer = normalizeServerId(local.getString("source-server", ""));
        if (!sourceServer.isBlank() && sourceServer.equals(normalizeServerId(currentServerId))) {
            return null;
        }

        PlayerSyncData data = new PlayerSyncData(
                deserializeItems(local.getString("data.storage", "")),
                deserializeItems(local.getString("data.armor", "")),
                deserializeItems(local.getString("data.extra", "")),
                deserializeItems(local.getString("data.enderchest", "")),
                deserializeItems(local.getString("data.hotbar", ""))
        );
        if (data.isEmpty()) {
            safeDelete(file);
            return null;
        }

        long updatedAt = local.getLong("updated-at", file.lastModified());
        return new LoadedTransfer(data, keyHash, file, updatedAt);
    }

    private void cleanupRemoteExpired() throws Exception {
        try (Connection connection = openConnection()) {
            ensureTable(connection);

            String sql = "DELETE FROM `" + resolveSafeTableName() + "` WHERE expires_at > 0 AND expires_at <= ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private void cleanupLocalExpired() {
        File folder = configSystem.getSyncLocalDatabaseFolder();
        if (!folder.exists() || !folder.isDirectory()) {
            return;
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".mysql"));
        if (files == null || files.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        for (File file : files) {
            try {
                YamlConfiguration local = YamlConfiguration.loadConfiguration(file);
                long expiresAt = local.getLong("expires-at", 0L);
                if (expiresAt <= 0L) {
                    long updatedAt = local.getLong("updated-at", 0L);
                    if (updatedAt > 0L) {
                        expiresAt = updatedAt + getExpireDurationMillis();
                    }
                }
                if (expiresAt > 0L && now >= expiresAt) {
                    safeDelete(file);
                }
            } catch (Exception exception) {
                plugin.getLogger().warning("Lokales Sync-Item-Cleanup fehlgeschlagen fuer: " + file.getPath() + " | " + exception.getMessage());
            }
        }
    }

    private Connection openConnection() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");

        String host = configSystem.getSyncDatabaseHost();
        int port = configSystem.getSyncDatabasePort();
        String database = configSystem.getSyncDatabaseName();
        String user = configSystem.getSyncDatabaseUser();
        String password = configSystem.getSyncDatabasePassword();

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8";

        return DriverManager.getConnection(url, user, password);
    }

    private void ensureTable(Connection connection) throws Exception {
        String sql = "CREATE TABLE IF NOT EXISTS `" + resolveSafeTableName() + "` (" +
                "uuid VARCHAR(36) NOT NULL," +
                "sync_key_hash VARCHAR(128) NOT NULL DEFAULT ''," +
                "source_server VARCHAR(128) NULL," +
                "expires_at BIGINT NOT NULL DEFAULT 0," +
                "storage_data LONGTEXT NULL," +
                "armor_data LONGTEXT NULL," +
                "extra_data LONGTEXT NULL," +
                "enderchest_data LONGTEXT NULL," +
                "hotbar_data LONGTEXT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (uuid, sync_key_hash)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }

        ensureColumn(connection, "sync_key_hash VARCHAR(128) NOT NULL DEFAULT ''");
        ensureColumn(connection, "source_server VARCHAR(128) NULL");
        ensureColumn(connection, "expires_at BIGINT NOT NULL DEFAULT 0");

        if (!remoteSchemaChecked) {
            ensureCompositePrimaryKey(connection);
            remoteSchemaChecked = true;
        }
    }

    private void ensureCompositePrimaryKey(Connection connection) throws Exception {
        String sql = "ALTER TABLE `" + resolveSafeTableName() + "` DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, sync_key_hash)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("can't drop") || message.contains("multiple primary key") || message.contains("duplicate")) {
                return;
            }
            throw exception;
        }
    }

    private void ensureColumn(Connection connection, String columnDefinition) throws Exception {
        String sql = "ALTER TABLE `" + resolveSafeTableName() + "` ADD COLUMN " + columnDefinition;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("duplicate column")) {
                return;
            }
            throw exception;
        }
    }

    private void deleteRemote(Connection connection, UUID uuid, String keyHash) throws Exception {
        String sql = "DELETE FROM `" + resolveSafeTableName() + "` WHERE uuid = ? AND sync_key_hash = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, keyHash);
            statement.executeUpdate();
        }
    }

    private String resolveSafeTableName() {
        return REMOTE_TABLE_NAME;
    }

    private File getLocalFile(UUID uuid, String syncKeyHash) {
        File folder = configSystem.getSyncLocalDatabaseFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Lokaler Sync-Ordner konnte nicht erstellt werden: " + folder.getPath());
        }
        return new File(folder, uuid + "." + syncKeyHash + ".mysql");
    }

    private File getLegacyLocalFile(UUID uuid) {
        File folder = configSystem.getSyncLocalDatabaseFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Lokaler Sync-Ordner konnte nicht erstellt werden: " + folder.getPath());
        }
        return new File(folder, uuid + ".mysql");
    }

    private String extractKeyHashFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String normalized = fileName.trim();
        if (!normalized.endsWith(".mysql")) {
            return "";
        }
        String withoutExt = normalized.substring(0, normalized.length() - ".mysql".length());
        int separator = withoutExt.indexOf('.');
        if (separator < 0 || separator == withoutExt.length() - 1) {
            return "";
        }
        return withoutExt.substring(separator + 1);
    }

    private long calculateExpiresAt() {
        return System.currentTimeMillis() + getExpireDurationMillis();
    }

    private long getExpireDurationMillis() {
        int seconds = configSystem.getSyncExpireAfterSeconds();
        if (seconds <= 0) {
            seconds = 180;
        }
        return seconds * 1000L;
    }

    private boolean isExpired(long expiresAt) {
        return expiresAt > 0L && System.currentTimeMillis() >= expiresAt;
    }

    private String resolveCurrentServerId() {
        String ip = plugin.getServer().getIp();
        if (ip == null || ip.isBlank()) {
            ip = "127.0.0.1";
        }
        int port = plugin.getServer().getPort();
        return normalizeServerId(ip + ":" + port);
    }

    private String normalizeServerId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasSyncKeysConfigured() {
        if (!resolveAllowedSyncKeyHashes().isEmpty()) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastKeyWarningMs >= KEY_WARNING_COOLDOWN_MS) {
            lastKeyWarningMs = now;
            plugin.getLogger().warning("Sync-Items ist aktiv, aber keine SyncItems.keys gesetzt. Sync wurde deaktiviert.");
        }
        return false;
    }

    private List<String> resolveAllowedSyncKeyHashes() {
        List<String> keys = configSystem.getSyncKeys();
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        List<String> hashes = new ArrayList<>();
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String normalized = key.trim();
            if (normalized.isBlank()) {
                continue;
            }
            String hashed = hashKey(normalized);
            if (!hashes.contains(hashed)) {
                hashes.add(hashed);
            }
        }
        return List.copyOf(hashes);
    }

    private String resolveSaveKeyHash(UUID uuid, List<String> allowedKeyHashes) {
        String active = activeSyncKeyByPlayer.get(uuid);
        if (active != null && !active.isBlank() && allowedKeyHashes.contains(active)) {
            return active;
        }
        return allowedKeyHashes.get(0);
    }

    private boolean isRemoteDatabaseUsable() {
        return configSystem.isDatabaseEnabled() && configSystem.isSyncRemoteDatabaseEnabled();
    }

    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 ist auf dieser Java-Umgebung nicht verfuegbar.", exception);
        }
    }

    private String serializeItems(ItemStack[] items) {
        if (items == null) {
            return "";
        }

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream outputStream = new BukkitObjectOutputStream(byteArrayOutputStream)) {

            outputStream.writeInt(items.length);
            for (ItemStack item : items) {
                outputStream.writeObject(item);
            }
            outputStream.flush();
            return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
        } catch (Exception exception) {
            plugin.getLogger().warning("Item-Serialisierung fehlgeschlagen: " + exception.getMessage());
            return "";
        }
    }

    private ItemStack[] deserializeItems(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }

        try {
            byte[] data = Base64.getDecoder().decode(base64);
            try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
                 BukkitObjectInputStream inputStream = new BukkitObjectInputStream(byteArrayInputStream)) {

                int length = inputStream.readInt();
                ItemStack[] items = new ItemStack[length];
                for (int i = 0; i < length; i++) {
                    Object object = inputStream.readObject();
                    items[i] = object instanceof ItemStack item ? item : null;
                }
                return items;
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Item-Deserialisierung fehlgeschlagen: " + exception.getMessage());
            return null;
        }
    }

    private ItemStack[] deepCopy(ItemStack[] source) {
        if (source == null) {
            return null;
        }
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = cloneItem(source[i]);
        }
        return copy;
    }

    private ItemStack[] adaptLength(ItemStack[] source, int size) {
        ItemStack[] adapted = new ItemStack[size];
        if (source == null) {
            return adapted;
        }
        int max = Math.min(source.length, size);
        for (int i = 0; i < max; i++) {
            adapted[i] = cloneItem(source[i]);
        }
        return adapted;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private void safeDelete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (!file.delete()) {
            plugin.getLogger().warning("Konnte lokale Sync-Datei nicht loeschen: " + file.getPath());
        }
    }

    private void warnRemoteFailure(String action, Exception exception) {
        if (!configSystem.isDatabaseEnabled() || !configSystem.isSyncRemoteDatabaseEnabled() || !configSystem.isSyncItemsEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRemoteWarningMs < REMOTE_WARNING_COOLDOWN_MS) {
            return;
        }
        lastRemoteWarningMs = now;

        plugin.getLogger().warning(
                "Sync-Items Remote-MySQL " + action + " fehlgeschlagen. Es wird lokal gespeichert/geladen. Grund: "
                        + exception.getMessage()
        );
    }

    private boolean hasAnyCategoryEnabled() {
        return configSystem.isSyncInventoryEnabled()
                || configSystem.isSyncEnderChestEnabled()
                || configSystem.isSyncHotbarEnabled();
    }

    private record LoadedTransfer(PlayerSyncData data, String syncKeyHash, File localFile, long updatedAt) {
    }

    public record PlayerSyncData(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack[] extra,
            ItemStack[] enderChest,
            ItemStack[] hotbar
    ) {
        public boolean isEmpty() {
            return isNullOrEmpty(storage)
                    && isNullOrEmpty(armor)
                    && isNullOrEmpty(extra)
                    && isNullOrEmpty(enderChest)
                    && isNullOrEmpty(hotbar);
        }

        private static boolean isNullOrEmpty(ItemStack[] items) {
            if (items == null || items.length == 0) {
                return true;
            }
            for (ItemStack item : items) {
                if (item != null) {
                    return false;
                }
            }
            return true;
        }
    }
}
