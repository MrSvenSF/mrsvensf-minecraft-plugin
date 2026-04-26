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
import java.util.ArrayList;
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

    private static final String DEFAULT_CHAT_PATH = "Chat/ChatMessageConfig.yml";
    private static final String DEFAULT_SPAWN_PATH = "Spawn/SpawnConfig.yml";
    private static final String DEFAULT_MESSAGE_PATH = "Chat/PLMessage.yml";
    private static final String DEFAULT_COMMANDS_PATH = "CommandsConfig.yml";

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

    private boolean colorsMiniMessageEnabled;
    private boolean colorsLegacyColorsEnabled;
    private String primeColorOne;
    private String primeColorTwo;

    private boolean updateEnabled;
    private boolean autoUpdateEnabled;
    private String configuredVersion;
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
