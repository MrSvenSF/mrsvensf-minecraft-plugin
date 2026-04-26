package DE.MrSvenSF.mrsvensf.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ConfigSystem {

    private static final String MAIN_CONFIG_RESOURCE_PATH = "MainConfig.yml";
    private static final String MAIN_CONFIG_FILE_NAME = "MainConfig.yml";
    private static final String LEGACY_MAIN_CONFIG_FILE_NAME = "config.yml";

    private static final String CHAT_RESOURCE_PATH = "Chat/ChatMessageConfig.yml";
    private static final String SPAWN_RESOURCE_PATH = "Spawn/SpawnConfig.yml";
    private static final String MESSAGE_RESOURCE_PATH = "Chat/PLMessage.yml";
    private static final String COMMANDS_RESOURCE_PATH = "CommandsConfig.yml";
    private static final String SYNC_ITEMS_RESOURCE_PATH = "SyncItem/SyncItem.yml";

    private static final String DEFAULT_CHAT_PATH = "Chat/ChatMessageConfig.yml";
    private static final String DEFAULT_SPAWN_PATH = "Spawn/SpawnConfig.yml";
    private static final String DEFAULT_MESSAGE_PATH = "Chat/PLMessage.yml";
    private static final String DEFAULT_COMMANDS_PATH = "CommandsConfig.yml";
    private static final String DEFAULT_SYNC_ITEMS_PATH = "SyncItem/SyncItem.yml";
    private static final String LEGACY_SYNC_ITEMS_PATH = "SyncItem/Synctem.yml";

    private final JavaPlugin plugin;

    private File mainConfigFile;
    private FileConfiguration mainConfig;
    private long mainConfigLastModified;

    private boolean chatEnabled;
    private boolean chatMiniMessageEnabled;
    private boolean chatLegacyColorsEnabled;
    private File chatConfigFile;
    private FileConfiguration chatConfig;
    private long chatConfigLastModified;
    private String configuredChatPath;

    private boolean spawnEnabled;
    private File spawnConfigFile;
    private FileConfiguration spawnConfig;
    private long spawnConfigLastModified;
    private String configuredSpawnPath;

    private File messageConfigFile;
    private FileConfiguration messageConfig;
    private long messageConfigLastModified;
    private String configuredMessagePath;

    private File commandsConfigFile;
    private FileConfiguration commandsConfig;
    private long commandsConfigLastModified;
    private String configuredCommandsPath;

    private File syncItemsConfigFile;
    private FileConfiguration syncItemsConfig;
    private long syncItemsConfigLastModified;
    private String configuredSyncItemsPath;

    private boolean colorsMiniMessageEnabled;
    private boolean colorsLegacyColorsEnabled;
    private String primeColorOne;
    private String primeColorTwo;

    private boolean updateEnabled;
    private boolean autoUpdateEnabled;
    private String configuredVersion;
    private boolean syncItemsEnabled;
    private boolean syncItemsMainEnabled;
    private boolean syncInventoryEnabled;
    private boolean syncEnderChestEnabled;
    private boolean syncHotbarEnabled;
    private boolean databaseEnabled;
    private boolean syncRemoteDatabaseEnabled;
    private String syncDatabaseHost;
    private int syncDatabasePort;
    private String syncDatabaseName;
    private String syncDatabaseUser;
    private String syncDatabasePassword;
    private String syncDatabaseTable;
    private List<String> syncKeys = List.of();
    private int syncExpireAfterSeconds;
    private List<String> configuredModerationCommands = List.of();
    private List<String> configuredPlayerCommands = List.of();

    public ConfigSystem(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Plugin-Ordner konnte nicht erstellt werden: " + plugin.getDataFolder().getPath());
        }

        this.mainConfigFile = resolveMainConfigFile();
        ensureAndAuditConfig(mainConfigFile, MAIN_CONFIG_RESOURCE_PATH, "main");
        reloadMainConfig();
        this.mainConfigLastModified = mainConfigFile.exists() ? mainConfigFile.lastModified() : 0L;

        readMainSettings(true);
    }

    public void reloadAll() {
        ensureAndAuditConfig(mainConfigFile, MAIN_CONFIG_RESOURCE_PATH, "main");
        reloadMainConfig();
        this.mainConfigLastModified = mainConfigFile.exists() ? mainConfigFile.lastModified() : 0L;
        readMainSettings(true);
    }

    public boolean isChatEnabled() {
        refreshMainConfigIfChanged();
        return chatEnabled;
    }

    public boolean isChatMiniMessageEnabled() {
        refreshMainConfigIfChanged();
        return chatMiniMessageEnabled;
    }

    public boolean isChatLegacyColorsEnabled() {
        refreshMainConfigIfChanged();
        return chatLegacyColorsEnabled;
    }

    public File getChatConfigFile() {
        refreshMainConfigIfChanged();
        return chatConfigFile;
    }

    public FileConfiguration getChatConfig() {
        refreshMainConfigIfChanged();
        refreshChatConfigIfChanged();
        return chatConfig == null ? new YamlConfiguration() : chatConfig;
    }

    public boolean isSpawnEnabled() {
        refreshMainConfigIfChanged();
        return spawnEnabled;
    }

    public FileConfiguration getSpawnConfig() {
        refreshMainConfigIfChanged();
        refreshSpawnConfigIfChanged();
        return spawnConfig == null ? new YamlConfiguration() : spawnConfig;
    }

    public FileConfiguration getMessageConfig() {
        refreshMainConfigIfChanged();
        refreshMessageConfigIfChanged();
        return messageConfig == null ? new YamlConfiguration() : messageConfig;
    }

    public boolean isColorsMiniMessageEnabled() {
        refreshMainConfigIfChanged();
        return colorsMiniMessageEnabled;
    }

    public boolean isColorsLegacyColorsEnabled() {
        refreshMainConfigIfChanged();
        return colorsLegacyColorsEnabled;
    }

    public String getPrimeColorOne() {
        refreshMainConfigIfChanged();
        return primeColorOne;
    }

    public String getPrimeColorTwo() {
        refreshMainConfigIfChanged();
        return primeColorTwo;
    }

    public List<String> getConfiguredModerationCommands() {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();
        return configuredModerationCommands;
    }

    public List<String> getConfiguredPlayerCommands() {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();
        return configuredPlayerCommands;
    }

    public List<CommandDefinition> getEnabledCommandDefinitions(String section) {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();

        if (commandsConfig == null) {
            return List.of();
        }

        ConfigurationSection sectionConfig = getConfigurationSectionIgnoreCase(commandsConfig, section);
        if (sectionConfig == null) {
            return List.of();
        }

        List<CommandDefinition> definitions = new ArrayList<>();
        for (String key : sectionConfig.getKeys(false)) {
            ConfigurationSection commandSection = sectionConfig.getConfigurationSection(key);
            if (commandSection == null) {
                continue;
            }

            String command = getSectionStringIgnoreCase(commandSection, "command", "");
            if (command == null || command.isBlank()) {
                continue;
            }

            String permission = getSectionStringIgnoreCase(commandSection, "permission", "");
            String action = getSectionStringIgnoreCase(commandSection, "action", "");
            action = inferCommandAction(section, key, command, action);

            definitions.add(new CommandDefinition(
                    key,
                    command.trim(),
                    permission == null ? "" : permission.trim(),
                    action
            ));
        }

        return List.copyOf(definitions);
    }

    public String getCommandPermission(String section, String key, String defaultPermission) {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();

        if (commandsConfig == null) {
            return defaultPermission;
        }

        ConfigurationSection commandSection = getConfigurationSectionIgnoreCase(commandsConfig, section + "." + key);
        if (commandSection == null) {
            return defaultPermission;
        }

        String value = getSectionStringIgnoreCase(commandSection, "permission", defaultPermission);
        if (value == null || value.isBlank()) {
            return defaultPermission;
        }
        return value.trim();
    }

    public String resolveModerationCommandKey(String input) {
        CommandDefinition definition = resolveModerationCommand(input);
        return definition == null ? null : definition.key();
    }

    public String getModerationSubCommand(String key, String fallback) {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();

        String configuredCommand = getCommandString("moderation", key, "");
        String parsed = parseModerationSubCommand(configuredCommand);
        if (parsed.isBlank()) {
            return fallback;
        }
        return parsed;
    }

    public String resolvePlayerCommandKey(String commandName) {
        CommandDefinition definition = resolvePlayerCommand(commandName);
        return definition == null ? null : definition.key();
    }

    public CommandDefinition resolveModerationCommand(String input) {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();

        if (input == null || input.isBlank()) {
            return null;
        }

        String normalizedInput = input.trim().toLowerCase(Locale.ROOT);
        for (CommandDefinition definition : getEnabledCommandDefinitions("moderation")) {
            if (definition.key().equalsIgnoreCase(normalizedInput)) {
                return definition;
            }

            String configuredSubCommand = parseModerationSubCommand(definition.command());
            if (!configuredSubCommand.isBlank() && configuredSubCommand.equalsIgnoreCase(normalizedInput)) {
                return definition;
            }
        }

        return null;
    }

    public CommandDefinition resolvePlayerCommand(String commandName) {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();

        List<CommandDefinition> definitions = getEnabledCommandDefinitions("player");
        if (definitions.isEmpty()) {
            return null;
        }

        String normalizedInput = commandName == null ? "" : commandName.trim().toLowerCase(Locale.ROOT);
        for (CommandDefinition definition : definitions) {
            if (definition.key().equalsIgnoreCase(normalizedInput)) {
                return definition;
            }

            String configuredPrimary = parsePrimaryCommand(definition.command());
            if (!configuredPrimary.isBlank() && configuredPrimary.equalsIgnoreCase(normalizedInput)) {
                return definition;
            }
        }

        if (definitions.size() == 1) {
            return definitions.get(0);
        }

        return null;
    }

    public String getCommandString(String section, String key, String defaultCommand) {
        refreshMainConfigIfChanged();
        refreshCommandsConfigIfChanged();

        if (commandsConfig == null) {
            return defaultCommand;
        }

        ConfigurationSection commandSection = getConfigurationSectionIgnoreCase(commandsConfig, section + "." + key);
        if (commandSection == null) {
            return defaultCommand;
        }

        String value = getSectionStringIgnoreCase(commandSection, "command", defaultCommand);
        if (value == null || value.isBlank()) {
            return defaultCommand;
        }
        return value.trim();
    }

    public boolean isUpdateEnabled() {
        refreshMainConfigIfChanged();
        return updateEnabled;
    }

    public boolean isAutoUpdateEnabled() {
        refreshMainConfigIfChanged();
        return autoUpdateEnabled;
    }

    public String getConfiguredVersion() {
        refreshMainConfigIfChanged();
        return configuredVersion;
    }

    public boolean isSyncItemsEnabled() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncItemsEnabled;
    }

    public boolean isSyncInventoryEnabled() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncInventoryEnabled;
    }

    public boolean isSyncEnderChestEnabled() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncEnderChestEnabled;
    }

    public boolean isSyncHotbarEnabled() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncHotbarEnabled;
    }

    public boolean isSyncRemoteDatabaseEnabled() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncRemoteDatabaseEnabled;
    }

    public boolean isDatabaseEnabled() {
        refreshMainConfigIfChanged();
        return databaseEnabled;
    }

    public String getSyncDatabaseHost() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncDatabaseHost;
    }

    public int getSyncDatabasePort() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncDatabasePort;
    }

    public String getSyncDatabaseName() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncDatabaseName;
    }

    public String getSyncDatabaseUser() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncDatabaseUser;
    }

    public String getSyncDatabasePassword() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncDatabasePassword;
    }

    public String getSyncDatabaseTable() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncDatabaseTable;
    }

    public List<String> getSyncKeys() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncKeys;
    }

    public String getSyncKey() {
        List<String> keys = getSyncKeys();
        if (keys.isEmpty()) {
            return "";
        }
        return keys.get(0);
    }

    public int getSyncExpireAfterSeconds() {
        refreshMainConfigIfChanged();
        refreshSyncItemsConfigIfChanged();
        return syncExpireAfterSeconds;
    }

    public File getSyncLocalDatabaseFolder() {
        refreshMainConfigIfChanged();
        File preferredFolder = new File(plugin.getDataFolder(), "DB/MySQL");
        File legacyFolder = new File(plugin.getDataFolder(), "MySQL");
        if (legacyFolder.exists() && !preferredFolder.exists()) {
            return legacyFolder;
        }
        return preferredFolder;
    }

    public void setConfiguredVersion(String version) {
        refreshMainConfigIfChanged();
        if (mainConfig == null) {
            return;
        }

        String safeVersion = version == null ? "" : version.trim();
        mainConfig.set("update.version", safeVersion);
        saveMainConfig();

        this.configuredVersion = safeVersion;
        this.mainConfigLastModified = mainConfigFile.exists() ? mainConfigFile.lastModified() : this.mainConfigLastModified;
    }

    public void saveSpawnConfig() {
        if (spawnConfig == null || spawnConfigFile == null) {
            return;
        }

        try {
            spawnConfig.save(spawnConfigFile);
            spawnConfigLastModified = spawnConfigFile.lastModified();
        } catch (IOException exception) {
            throw new IllegalStateException("Spawn-Config konnte nicht gespeichert werden: " + spawnConfigFile.getPath(), exception);
        }
    }

    private void refreshMainConfigIfChanged() {
        if (mainConfigFile == null || !mainConfigFile.exists()) {
            return;
        }

        long currentLastModified = mainConfigFile.lastModified();
        if (currentLastModified != mainConfigLastModified) {
            reloadMainConfig();
            mainConfigLastModified = currentLastModified;
            readMainSettings(false);
        }
    }

    private void readMainSettings(boolean firstLoad) {
        FileConfiguration config = mainConfig == null ? new YamlConfiguration() : mainConfig;

        this.colorsMiniMessageEnabled = config.getBoolean("colors.features.mini-message", true);
        this.colorsLegacyColorsEnabled = config.getBoolean("colors.features.legacy-colors", true);

        this.primeColorOne = config.getString("colors.Prime-Colors-One", "{message}");
        if (primeColorOne == null || primeColorOne.isBlank()) {
            this.primeColorOne = "{message}";
        }

        this.primeColorTwo = config.getString("colors.Prime-Colors-Two", "{message}");
        if (primeColorTwo == null || primeColorTwo.isBlank()) {
            this.primeColorTwo = "{message}";
        }

        this.updateEnabled = config.getBoolean("update.on", true);
        this.autoUpdateEnabled = config.getBoolean("update.auto-update", false);
        this.configuredVersion = config.getString("update.version", plugin.getPluginMeta().getVersion());
        if (configuredVersion == null || configuredVersion.isBlank()) {
            this.configuredVersion = plugin.getPluginMeta().getVersion();
        }

        this.chatEnabled = config.getBoolean("config.Chat.on", true);
        this.chatMiniMessageEnabled = config.getBoolean("config.Chat.features.mini-message", true);
        this.chatLegacyColorsEnabled = config.getBoolean("config.Chat.features.legacy-colors", true);
        this.spawnEnabled = config.getBoolean("config.Spawn.on", true);
        this.syncItemsMainEnabled = getBooleanAnyPath(
                config,
                true,
                "config.SyncItems.on",
                "config.Sync-Items.on",
                "config.Sync Items.on",
                "sync-items.on",
                "Sync-Items.on",
                "Sync Items.on",
                "sync.items.on"
        );
        readDatabaseSettings(config);

        String chatPath = config.getString("config.Chat.path", DEFAULT_CHAT_PATH);
        if (chatPath == null || chatPath.isBlank()) {
            chatPath = DEFAULT_CHAT_PATH;
        }
        boolean chatPathChanged = firstLoad || configuredChatPath == null || !configuredChatPath.equals(chatPath);
        if (chatPathChanged) {
            configuredChatPath = chatPath;
            chatConfigFile = resolveServerPath(chatPath);
        }
        ConfigAuditResult chatAudit = ensureAndAuditConfig(chatConfigFile, CHAT_RESOURCE_PATH, "chat");
        if (chatPathChanged || chatAudit.changed()) {
            reloadChatConfig();
        }

        String spawnPath = config.getString("config.Spawn.path", DEFAULT_SPAWN_PATH);
        if (spawnPath == null || spawnPath.isBlank()) {
            spawnPath = DEFAULT_SPAWN_PATH;
        }
        boolean spawnPathChanged = firstLoad || configuredSpawnPath == null || !configuredSpawnPath.equals(spawnPath);
        if (spawnPathChanged) {
            configuredSpawnPath = spawnPath;
            spawnConfigFile = resolveServerPath(spawnPath);
        }
        ConfigAuditResult spawnAudit = ensureAndAuditConfig(spawnConfigFile, SPAWN_RESOURCE_PATH, "spawn");
        if (spawnPathChanged || spawnAudit.changed()) {
            reloadSpawnConfig();
        }

        String messagePath = config.getString("config.PluginMessages.path", DEFAULT_MESSAGE_PATH);
        if (messagePath == null || messagePath.isBlank()) {
            messagePath = DEFAULT_MESSAGE_PATH;
        }
        boolean messagePathChanged = firstLoad || configuredMessagePath == null || !configuredMessagePath.equals(messagePath);
        if (messagePathChanged) {
            configuredMessagePath = messagePath;
            messageConfigFile = resolveServerPath(messagePath);
        }
        ConfigAuditResult messageAudit = ensureAndAuditConfig(messageConfigFile, MESSAGE_RESOURCE_PATH, "plugin-messages");
        if (messagePathChanged || messageAudit.changed()) {
            reloadMessageConfig();
        }

        String commandsPath = config.getString("config.Commands.path", DEFAULT_COMMANDS_PATH);
        if (commandsPath == null || commandsPath.isBlank()) {
            commandsPath = DEFAULT_COMMANDS_PATH;
        }
        boolean commandsPathChanged = firstLoad || configuredCommandsPath == null || !configuredCommandsPath.equals(commandsPath);
        if (commandsPathChanged) {
            configuredCommandsPath = commandsPath;
            commandsConfigFile = resolveServerPath(commandsPath);
        }
        ConfigAuditResult commandsAudit = ensureAndAuditConfig(commandsConfigFile, COMMANDS_RESOURCE_PATH, "commands");
        if (commandsPathChanged || commandsAudit.changed()) {
            reloadCommandsConfig();
        }

        String syncItemsPath = getStringAnyPath(
                config,
                DEFAULT_SYNC_ITEMS_PATH,
                "config.SyncItems.path",
                "config.Sync-Items.path",
                "config.Sync Items.path",
                "config.SyncItem.path",
                "config.Sync.path"
        );
        if (syncItemsPath == null || syncItemsPath.isBlank()) {
            syncItemsPath = DEFAULT_SYNC_ITEMS_PATH;
        }
        String normalizedSyncItemsPath = normalizeSyncItemsPath(syncItemsPath);
        if (!normalizedSyncItemsPath.equals(syncItemsPath)) {
            migrateLegacySyncItemsFile(syncItemsPath, normalizedSyncItemsPath);
            syncItemsPath = normalizedSyncItemsPath;
            if (mainConfig != null) {
                mainConfig.set("config.SyncItems.path", normalizedSyncItemsPath);
                saveMainConfig();
                mainConfigLastModified = mainConfigFile.exists() ? mainConfigFile.lastModified() : mainConfigLastModified;
            }
        }
        boolean syncItemsPathChanged = firstLoad
                || configuredSyncItemsPath == null
                || !configuredSyncItemsPath.equals(syncItemsPath);
        if (syncItemsPathChanged) {
            configuredSyncItemsPath = syncItemsPath;
            syncItemsConfigFile = resolveServerPath(syncItemsPath);
        }
        ConfigAuditResult syncItemsAudit = ensureAndAuditConfig(syncItemsConfigFile, SYNC_ITEMS_RESOURCE_PATH, "sync-items");
        if (syncItemsPathChanged || syncItemsAudit.changed()) {
            reloadSyncItemsConfig();
        }

        readSyncItemsSettings(config, syncItemsConfig == null ? new YamlConfiguration() : syncItemsConfig);
        readCommandSettings(config);
    }

    private void refreshMessageConfigIfChanged() {
        if (messageConfigFile == null || !messageConfigFile.exists()) {
            return;
        }

        long currentLastModified = messageConfigFile.lastModified();
        if (currentLastModified != messageConfigLastModified) {
            reloadMessageConfig();
        }
    }

    private void refreshCommandsConfigIfChanged() {
        if (commandsConfigFile == null || !commandsConfigFile.exists()) {
            return;
        }

        long currentLastModified = commandsConfigFile.lastModified();
        if (currentLastModified != commandsConfigLastModified) {
            reloadCommandsConfig();
            readCommandSettings(mainConfig == null ? new YamlConfiguration() : mainConfig);
        }
    }

    private void refreshSyncItemsConfigIfChanged() {
        if (syncItemsConfigFile == null || !syncItemsConfigFile.exists()) {
            return;
        }

        long currentLastModified = syncItemsConfigFile.lastModified();
        if (currentLastModified != syncItemsConfigLastModified) {
            reloadSyncItemsConfig();
            readSyncItemsSettings(mainConfig == null ? new YamlConfiguration() : mainConfig, syncItemsConfig);
        }
    }

    private void refreshChatConfigIfChanged() {
        if (!chatEnabled || chatConfigFile == null || !chatConfigFile.exists()) {
            return;
        }

        long currentLastModified = chatConfigFile.lastModified();
        if (currentLastModified != chatConfigLastModified) {
            reloadChatConfig();
        }
    }

    private void refreshSpawnConfigIfChanged() {
        if (spawnConfigFile == null || !spawnConfigFile.exists()) {
            return;
        }

        long currentLastModified = spawnConfigFile.lastModified();
        if (currentLastModified != spawnConfigLastModified) {
            reloadSpawnConfig();
        }
    }

    private void reloadMainConfig() {
        mainConfig = YamlConfiguration.loadConfiguration(mainConfigFile);
    }

    private void saveMainConfig() {
        try {
            mainConfig.save(mainConfigFile);
        } catch (IOException exception) {
            throw new IllegalStateException("MainConfig konnte nicht gespeichert werden: " + mainConfigFile.getPath(), exception);
        }
    }

    private void reloadChatConfig() {
        chatConfig = YamlConfiguration.loadConfiguration(chatConfigFile);
        chatConfigLastModified = chatConfigFile.lastModified();
    }

    private void reloadSpawnConfig() {
        spawnConfig = YamlConfiguration.loadConfiguration(spawnConfigFile);
        spawnConfigLastModified = spawnConfigFile.lastModified();
    }

    private void reloadMessageConfig() {
        messageConfig = YamlConfiguration.loadConfiguration(messageConfigFile);
        messageConfigLastModified = messageConfigFile.lastModified();
    }

    private void reloadCommandsConfig() {
        commandsConfig = YamlConfiguration.loadConfiguration(commandsConfigFile);
        commandsConfigLastModified = commandsConfigFile.lastModified();
    }

    private void reloadSyncItemsConfig() {
        syncItemsConfig = YamlConfiguration.loadConfiguration(syncItemsConfigFile);
        syncItemsConfigLastModified = syncItemsConfigFile.lastModified();
    }

    private String normalizeSyncItemsPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return DEFAULT_SYNC_ITEMS_PATH;
        }

        String normalized = configuredPath.replace('\\', '/');
        if (normalized.equalsIgnoreCase(LEGACY_SYNC_ITEMS_PATH) || normalized.toLowerCase(Locale.ROOT).endsWith("/synctem.yml")) {
            return DEFAULT_SYNC_ITEMS_PATH;
        }
        return configuredPath;
    }

    private void migrateLegacySyncItemsFile(String oldPath, String newPath) {
        if (oldPath == null || oldPath.isBlank() || newPath == null || newPath.isBlank()) {
            return;
        }

        File oldFile = resolveServerPath(oldPath);
        File newFile = resolveServerPath(newPath);
        if (!oldFile.exists() || newFile.exists()) {
            return;
        }

        ensureParentDirectoryExists(newFile);
        try {
            Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("SyncItems-Config wurde auf den neuen Dateinamen migriert: " + newFile.getPath());
        } catch (IOException exception) {
            plugin.getLogger().warning("SyncItems-Config konnte nicht migriert werden: " + exception.getMessage());
        }
    }

    private File resolveMainConfigFile() {
        File preferred = new File(plugin.getDataFolder(), MAIN_CONFIG_FILE_NAME);
        if (preferred.exists()) {
            return preferred;
        }

        File legacy = new File(plugin.getDataFolder(), LEGACY_MAIN_CONFIG_FILE_NAME);
        if (legacy.exists()) {
            return legacy;
        }

        return preferred;
    }

    private ConfigAuditResult ensureAndAuditConfig(File targetFile, String resourcePath, String configId) {
        if (targetFile == null) {
            throw new IllegalStateException("Konfigurationspfad ist nicht gesetzt (" + configId + ").");
        }

        boolean created = false;
        try {
            YamlConfiguration defaultConfig = loadResourceConfig(resourcePath);
            boolean resourceAvailable = defaultConfig != null;

            if (!targetFile.exists()) {
                if (resourceAvailable) {
                    createConfigFileFromResource(targetFile, resourcePath);
                } else {
                    createEmptyConfigFile(targetFile);
                    plugin.getLogger().warning(
                            "Default-Resource fehlt fuer Config '" + configId + "' (" + resourcePath + "). "
                                    + "Leere Datei erstellt: " + targetFile.getPath()
                    );
                }
                created = true;
            }

            YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(targetFile);
            List<String> missingKeys = new ArrayList<>();

            if (resourceAvailable) {
                mergeMissingDefaults("", defaultConfig, currentConfig, missingKeys);
            }

            boolean changed = created || !missingKeys.isEmpty();
            if (!missingKeys.isEmpty()) {
                currentConfig.save(targetFile);
                plugin.getLogger().info(
                        "Config '" + configId + "' repariert: " + missingKeys.size() + " fehlende Keys ergaenzt."
                );
            }

            return new ConfigAuditResult(changed);
        } catch (Exception exception) {
            throw new IllegalStateException("Config konnte nicht geprueft werden (" + configId + "): " + targetFile.getPath(), exception);
        }
    }

    private void createConfigFileFromResource(File targetFile, String resourcePath) {
        ensureParentDirectoryExists(targetFile);

        try (InputStream inputStream = plugin.getResource(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Resource fehlt: " + resourcePath);
            }
            Files.copy(inputStream, targetFile.toPath());
        } catch (IOException exception) {
            throw new IllegalStateException("Config konnte nicht erstellt werden: " + targetFile.getPath(), exception);
        }
    }

    private void createEmptyConfigFile(File targetFile) {
        ensureParentDirectoryExists(targetFile);

        YamlConfiguration empty = new YamlConfiguration();
        try {
            empty.save(targetFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Leere Config konnte nicht erstellt werden: " + targetFile.getPath(), exception);
        }
    }

    private void ensureParentDirectoryExists(File targetFile) {
        File parentDirectory = targetFile.getParentFile();
        if (parentDirectory != null && !parentDirectory.exists() && !parentDirectory.mkdirs()) {
            throw new IllegalStateException("Ordner konnte nicht erstellt werden: " + parentDirectory.getPath());
        }
    }

    private YamlConfiguration loadResourceConfig(String resourcePath) {
        try (InputStream inputStream = plugin.getResource(resourcePath)) {
            if (inputStream == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Resource konnte nicht gelesen werden: " + resourcePath, exception);
        }
    }

    private void mergeMissingDefaults(
            String parentPath,
            ConfigurationSection defaultSection,
            ConfigurationSection currentSection,
            List<String> missingKeys
    ) {
        for (String key : defaultSection.getKeys(false)) {
            String path = joinPath(parentPath, key);
            boolean defaultIsSection = defaultSection.isConfigurationSection(key);

            if (defaultIsSection) {
                if (!currentSection.contains(key)) {
                    currentSection.createSection(key);
                    missingKeys.add(path);
                }

                if (!currentSection.isConfigurationSection(key)) {
                    continue;
                }

                ConfigurationSection nextDefault = defaultSection.getConfigurationSection(key);
                ConfigurationSection nextCurrent = currentSection.getConfigurationSection(key);
                if (nextDefault != null && nextCurrent != null) {
                    mergeMissingDefaults(path, nextDefault, nextCurrent, missingKeys);
                }
                continue;
            }

            if (!currentSection.contains(key)) {
                currentSection.set(key, defaultSection.get(key));
                missingKeys.add(path);
            }
        }
    }

    private String joinPath(String parentPath, String key) {
        if (parentPath == null || parentPath.isBlank()) {
            return key;
        }
        return parentPath + "." + key;
    }

    private void readDatabaseSettings(FileConfiguration config) {
        ConfigurationSection dbSection = getConfigurationSectionIgnoreCase(config, "DB");
        if (dbSection == null) {
            dbSection = getConfigurationSectionIgnoreCase(config, "database");
        }
        if (dbSection == null) {
            dbSection = getConfigurationSectionIgnoreCase(config, "config.DB");
        }
        if (dbSection == null) {
            dbSection = getConfigurationSectionIgnoreCase(config, "config.database");
        }

        this.databaseEnabled = getSectionBooleanIgnoreCase(dbSection, "on", getBooleanAnyPath(
                config,
                true,
                "DB.on",
                "DB.On",
                "database.on",
                "db.on",
                "config.DB.on",
                "config.database.on"
        ));

        String address = getSectionStringIgnoreCase(dbSection, "address", getStringAnyPath(
                config,
                "127.0.0.1:3306",
                "DB.address",
                "DB.Address",
                "database.address",
                "db.address",
                "config.DB.address"
        ));
        HostAndPort parsedAddress = parseHostAndPort(address == null ? "" : address);
        this.syncDatabaseHost = parsedAddress.host().isBlank() ? "127.0.0.1" : parsedAddress.host();
        this.syncDatabasePort = parsedAddress.port() > 0 ? parsedAddress.port() : 3306;

        this.syncDatabaseName = getSectionStringIgnoreCase(dbSection, "database", getStringAnyPath(
                config,
                "mrsvensf",
                "DB.database",
                "database.database",
                "db.database"
        ));
        this.syncDatabaseUser = getSectionStringIgnoreCase(dbSection, "username", getStringAnyPath(
                config,
                "root",
                "DB.username",
                "DB.user",
                "database.username",
                "database.user",
                "db.username",
                "db.user"
        ));
        if (syncDatabaseUser == null || syncDatabaseUser.isBlank()) {
            this.syncDatabaseUser = getSectionStringIgnoreCase(dbSection, "user", "root");
        }
        this.syncDatabasePassword = getSectionStringIgnoreCase(dbSection, "password", getStringAnyPath(
                config,
                "",
                "DB.password",
                "database.password",
                "db.password"
        ));

        // Table is intentionally fixed in code for stable updates/migrations.
        this.syncDatabaseTable = "mrsvensf_sync_items";
        this.syncRemoteDatabaseEnabled = databaseEnabled;
    }

    private void readSyncItemsSettings(FileConfiguration mainConfigData, FileConfiguration syncData) {
        boolean fallbackSyncOn = getBooleanAnyPath(
                mainConfigData,
                syncItemsMainEnabled,
                "config.SyncItems.on",
                "config.Sync-Items.on",
                "config.Sync Items.on"
        );
        boolean fallbackInventoryOn = getBooleanAnyPath(
                mainConfigData,
                true,
                "config.SyncItems.inventory.on",
                "sync-items.inventory",
                "sync-items.inventory.on"
        );
        boolean fallbackEnderChestOn = getBooleanAnyPath(
                mainConfigData,
                true,
                "config.SyncItems.enderchest.on",
                "sync-items.enderchest",
                "sync-items.enderchest.on"
        );
        boolean fallbackHotbarOn = getBooleanAnyPath(
                mainConfigData,
                true,
                "config.SyncItems.hotbar.on",
                "sync-items.hotbar",
                "sync-items.hotbar.on"
        );
        int fallbackExpireSeconds = getIntAnyPath(
                mainConfigData,
                180,
                "config.SyncItems.expire-after-seconds",
                "sync-items.expire-after-seconds",
                "sync-items.delete-after-seconds"
        );

        boolean syncItemsConfigEnabled = getBooleanAnyPath(
                syncData,
                fallbackSyncOn,
                "SyncItems.on",
                "sync-items.on",
                "Sync-Items.on",
                "Sync Items.on",
                "sync.items.on"
        );
        this.syncItemsEnabled = syncItemsMainEnabled && syncItemsConfigEnabled;
        this.syncInventoryEnabled = getBooleanAnyPath(
                syncData,
                fallbackInventoryOn,
                "SyncItems.inventory.on",
                "SyncItems.inventory",
                "sync-items.inventory",
                "sync-items.inventory.on"
        );
        this.syncEnderChestEnabled = getBooleanAnyPath(
                syncData,
                fallbackEnderChestOn,
                "SyncItems.enderchest.on",
                "SyncItems.enderchest",
                "sync-items.enderchest",
                "sync-items.ender-chest",
                "sync-items.enderchest.on"
        );
        this.syncHotbarEnabled = getBooleanAnyPath(
                syncData,
                fallbackHotbarOn,
                "SyncItems.hotbar.on",
                "SyncItems.hotbar",
                "sync-items.hotbar",
                "sync-items.hotbar.on"
        );
        this.syncExpireAfterSeconds = getIntAnyPath(
                syncData,
                fallbackExpireSeconds,
                "SyncItems.expire-after-seconds",
                "SyncItems.delete-after-seconds",
                "sync-items.expire-after-seconds",
                "sync-items.delete-after-seconds"
        );
        if (syncExpireAfterSeconds <= 0) {
            this.syncExpireAfterSeconds = 180;
        }

        LinkedHashSet<String> normalizedKeys = new LinkedHashSet<>();
        addNormalizedKeys(normalizedKeys, getStringListAnyPath(
                syncData,
                "SyncItems.keys",
                "sync-items.keys",
                "sync.items.keys"
        ));
        addNormalizedKeys(normalizedKeys, getStringListAnyPath(
                mainConfigData,
                "config.SyncItems.keys",
                "sync-items.keys",
                "sync.items.keys"
        ));

        String legacySingleKey = getStringAnyPath(
                syncData,
                "",
                "SyncItems.key",
                "sync-items.key",
                "sync.items.key"
        );
        if (legacySingleKey != null && !legacySingleKey.isBlank()) {
            normalizedKeys.add(legacySingleKey.trim());
        }

        String legacyMainSingleKey = getStringAnyPath(
                mainConfigData,
                "",
                "config.SyncItems.key",
                "sync-items.key",
                "sync.items.key"
        );
        if (legacyMainSingleKey != null && !legacyMainSingleKey.isBlank()) {
            normalizedKeys.add(legacyMainSingleKey.trim());
        }

        this.syncKeys = List.copyOf(normalizedKeys);
    }

    private HostAndPort parseHostAndPort(String value) {
        if (value == null || value.isBlank()) {
            return new HostAndPort("", -1);
        }

        String normalized = value.trim();
        int colonIndex = normalized.lastIndexOf(':');
        if (colonIndex <= 0 || colonIndex == normalized.length() - 1) {
            return new HostAndPort(normalized, -1);
        }

        String host = normalized.substring(0, colonIndex).trim();
        String portRaw = normalized.substring(colonIndex + 1).trim();
        try {
            int parsedPort = Integer.parseInt(portRaw);
            return new HostAndPort(host, parsedPort);
        } catch (NumberFormatException ignored) {
            return new HostAndPort(normalized, -1);
        }
    }

    private void readCommandSettings(FileConfiguration mainConfigData) {
        List<String> moderationCommands = extractCommandList("moderation");
        List<String> playerCommands = extractCommandList("player");

        if (moderationCommands.isEmpty() && playerCommands.isEmpty()) {
            List<String> legacyModeration = mainConfigData.getStringList("commands.moderation");
            List<String> legacyPlayer = mainConfigData.getStringList("commands.player");
            List<String> legacyFlat = mainConfigData.getStringList("commands");

            if (!legacyModeration.isEmpty()) {
                moderationCommands = List.copyOf(new ArrayList<>(legacyModeration));
            } else if (!legacyFlat.isEmpty()) {
                moderationCommands = List.copyOf(new ArrayList<>(legacyFlat));
            }

            if (!legacyPlayer.isEmpty()) {
                playerCommands = List.copyOf(new ArrayList<>(legacyPlayer));
            }
        }

        configuredModerationCommands = moderationCommands;
        configuredPlayerCommands = playerCommands;
    }

    private List<String> extractCommandList(String sectionPath) {
        if (commandsConfig == null) {
            return List.of();
        }

        ConfigurationSection section = getConfigurationSectionIgnoreCase(commandsConfig, sectionPath);
        if (section == null) {
            return List.of();
        }

        List<String> commands = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection commandSection = section.getConfigurationSection(key);
            if (commandSection == null) {
                continue;
            }

            String commandValue = getSectionStringIgnoreCase(commandSection, "command", "");
            if (commandValue == null || commandValue.isBlank()) {
                continue;
            }
            commands.add(commandValue.trim());
        }

        if (commands.isEmpty()) {
            List<String> rawList = commandsConfig.getStringList(sectionPath);
            if (!rawList.isEmpty()) {
                return List.copyOf(new ArrayList<>(rawList));
            }
        }

        return List.copyOf(commands);
    }

    private String parseModerationSubCommand(String fullCommand) {
        if (fullCommand == null || fullCommand.isBlank()) {
            return "";
        }

        String normalized = fullCommand.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        String[] parts = normalized.split("\\s+");
        if (parts.length >= 2) {
            return parts[1].trim();
        }
        if (parts.length == 1) {
            return parts[0].trim();
        }
        return "";
    }

    private String parsePrimaryCommand(String fullCommand) {
        if (fullCommand == null || fullCommand.isBlank()) {
            return "";
        }

        String normalized = fullCommand.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        String[] parts = normalized.split("\\s+");
        if (parts.length == 0) {
            return "";
        }
        return parts[0].trim();
    }

    private String getSectionStringIgnoreCase(ConfigurationSection section, String key, String defaultValue) {
        if (section == null) {
            return defaultValue;
        }

        for (String existingKey : section.getKeys(false)) {
            if (existingKey.equalsIgnoreCase(key)) {
                String value = section.getString(existingKey);
                return value == null ? defaultValue : value;
            }
        }

        return defaultValue;
    }

    private boolean getSectionBooleanIgnoreCase(ConfigurationSection section, String key, boolean defaultValue) {
        if (section == null) {
            return defaultValue;
        }

        for (String existingKey : section.getKeys(false)) {
            if (existingKey.equalsIgnoreCase(key)) {
                return section.getBoolean(existingKey, defaultValue);
            }
        }

        return defaultValue;
    }

    private boolean getBooleanAnyPath(FileConfiguration config, boolean defaultValue, String... paths) {
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (config.contains(path)) {
                return config.getBoolean(path, defaultValue);
            }
        }
        return defaultValue;
    }

    private String getStringAnyPath(FileConfiguration config, String defaultValue, String... paths) {
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (!config.contains(path)) {
                continue;
            }
            String value = config.getString(path);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return defaultValue;
    }

    private List<String> getStringListAnyPath(FileConfiguration config, String... paths) {
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (!config.contains(path)) {
                continue;
            }
            List<String> values = config.getStringList(path);
            if (values != null && !values.isEmpty()) {
                return values;
            }
        }
        return List.of();
    }

    private void addNormalizedKeys(LinkedHashSet<String> target, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String normalized = key.trim();
            if (!normalized.isBlank()) {
                target.add(normalized);
            }
        }
    }

    private int getIntAnyPath(FileConfiguration config, int defaultValue, String... paths) {
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (config.contains(path)) {
                return config.getInt(path, defaultValue);
            }
        }
        return defaultValue;
    }

    private String inferCommandAction(String section, String key, String command, String configuredAction) {
        if (configuredAction != null && !configuredAction.isBlank()) {
            return configuredAction.trim().toLowerCase(Locale.ROOT);
        }

        String normalizedSection = section == null ? "" : section.trim().toLowerCase(Locale.ROOT);
        if ("moderation".equals(normalizedSection)) {
            String parsedSubCommand = parseModerationSubCommand(command);
            return inferKnownAction(key, parsedSubCommand, List.of("reload", "version", "update", "setspawn"));
        }
        if ("player".equals(normalizedSection)) {
            String parsedPrimary = parsePrimaryCommand(command);
            return inferKnownAction(key, parsedPrimary, List.of("spawn"));
        }

        return "";
    }

    private String inferKnownAction(String key, String parsedCommandPart, List<String> knownActions) {
        for (String action : knownActions) {
            if (key != null && key.equalsIgnoreCase(action)) {
                return action;
            }
            if (parsedCommandPart != null && parsedCommandPart.equalsIgnoreCase(action)) {
                return action;
            }
        }
        return "";
    }

    private ConfigurationSection getConfigurationSectionIgnoreCase(ConfigurationSection root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        String[] parts = path.split("\\.");
        ConfigurationSection current = root;
        for (String part : parts) {
            current = getChildSectionIgnoreCase(current, part);
            if (current == null) {
                return null;
            }
        }

        return current;
    }

    private ConfigurationSection getChildSectionIgnoreCase(ConfigurationSection parent, String childKey) {
        if (parent == null || childKey == null || childKey.isBlank()) {
            return null;
        }

        for (String existingKey : parent.getKeys(false)) {
            if (!existingKey.equalsIgnoreCase(childKey)) {
                continue;
            }
            if (!parent.isConfigurationSection(existingKey)) {
                return null;
            }
            return parent.getConfigurationSection(existingKey);
        }

        return null;
    }

    private record HostAndPort(String host, int port) {
    }

    private record ConfigAuditResult(boolean changed) {
    }

    public record CommandDefinition(String key, String command, String permission, String action) {
    }

    private File resolveServerPath(String configuredPath) {
        Path configured = Path.of(configuredPath).normalize();
        if (configured.isAbsolute()) {
            return configured.toFile();
        }

        String normalizedPathText = configured.toString().replace('\\', '/').toLowerCase();
        if (normalizedPathText.startsWith("plugins/")) {
            return Path.of("").resolve(configured).normalize().toFile();
        }

        return plugin.getDataFolder().toPath().resolve(configured).normalize().toFile();
    }
}
