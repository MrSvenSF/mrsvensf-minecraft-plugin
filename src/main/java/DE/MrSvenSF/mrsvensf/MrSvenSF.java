package DE.MrSvenSF.mrsvensf;

import DE.MrSvenSF.mrsvensf.chat.ChatListener;
import DE.MrSvenSF.mrsvensf.command.MrSvenSFCommand;
import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import DE.MrSvenSF.mrsvensf.message.MessageService;
import DE.MrSvenSF.mrsvensf.spawn.SpawnListener;
import DE.MrSvenSF.mrsvensf.spawn.SpawnService;
import DE.MrSvenSF.mrsvensf.update.UpdateService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class MrSvenSF extends JavaPlugin {

    private static final String DATA_FOLDER_NAME = "MrSvenSF";
    private static final String[] LEGACY_FOLDER_NAMES = {"mrsvensf", "mrsvensf.com"};

    private ConfigSystem configSystem;
    private ChatListener chatListener;
    private SpawnService spawnService;
    private SpawnListener spawnListener;
    private MessageService messageService;
    private UpdateService updateService;

    @Override
    public void onEnable() {
        normalizePluginDataFolder();

        try {
            this.configSystem = new ConfigSystem(this);
            this.configSystem.initialize();
        } catch (Exception exception) {
            getLogger().severe("Config-System konnte nicht initialisiert werden: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.messageService = new MessageService(configSystem);
        this.chatListener = new ChatListener(this, configSystem);
        getServer().getPluginManager().registerEvents(chatListener, this);
        this.spawnService = new SpawnService(configSystem);
        this.spawnListener = new SpawnListener(this, spawnService);
        getServer().getPluginManager().registerEvents(spawnListener, this);
        this.updateService = new UpdateService(this, configSystem);
        this.updateService.start();

        registerCommands();

        if (configSystem.isChatEnabled()) {
            getLogger().info("Chat-System geladen mit: " + configSystem.getChatConfigFile().getPath());
        } else {
            getLogger().info("Chat-System ist in MainConfig.yml deaktiviert.");
        }
    }

    @Override
    public void onDisable() {
        if (updateService != null) {
            updateService.stop();
        }
    }

    public boolean reloadAllConfigs() {
        try {
            configSystem.reloadAll();
            if (chatListener != null) {
                chatListener.reloadRuntimeState();
            }
            if (updateService != null) {
                updateService.restartAutoUpdater();
            }
            return true;
        } catch (Exception exception) {
            getLogger().severe("Reload fehlgeschlagen: " + exception.getMessage());
            return false;
        }
    }

    private void registerCommands() {
        PluginCommand adminCommand = getCommand("mrsvensf");
        PluginCommand spawnCommand = getCommand("spawn");
        if (adminCommand == null || spawnCommand == null) {
            getLogger().severe("Commands konnten nicht registriert werden.");
            return;
        }

        MrSvenSFCommand executor = new MrSvenSFCommand(this, messageService, updateService, spawnService);
        adminCommand.setExecutor(executor);
        adminCommand.setTabCompleter(executor);
        spawnCommand.setExecutor(executor);
        spawnCommand.setTabCompleter(executor);
    }

    public ConfigSystem getConfigSystem() {
        return configSystem;
    }

    private void normalizePluginDataFolder() {
        File dataFolder = getDataFolder();
        File pluginsFolder = dataFolder.getParentFile();
        if (pluginsFolder == null || !pluginsFolder.exists()) {
            return;
        }

        File targetFolder = new File(pluginsFolder, DATA_FOLDER_NAME);
        File[] directories = pluginsFolder.listFiles(File::isDirectory);
        if (directories != null) {
            for (File directory : directories) {
                if (!directory.getName().equalsIgnoreCase(DATA_FOLDER_NAME)) {
                    continue;
                }
                if (directory.getName().equals(DATA_FOLDER_NAME)) {
                    return;
                }

                File tempFolder = new File(pluginsFolder, DATA_FOLDER_NAME + "_tmp_casefix");
                if (tempFolder.exists()) {
                    return;
                }

                try {
                    Files.move(directory.toPath(), tempFolder.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tempFolder.toPath(), targetFolder.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    getLogger().info("Plugin-Ordner wurde auf '" + DATA_FOLDER_NAME + "' normalisiert.");
                } catch (Exception exception) {
                    getLogger().warning("Plugin-Ordner konnte nicht normalisiert werden: " + exception.getMessage());
                }
                return;
            }
        }

        for (String legacyName : LEGACY_FOLDER_NAMES) {
            File legacyFolder = new File(pluginsFolder, legacyName);
            if (!legacyFolder.exists() || !legacyFolder.isDirectory()) {
                continue;
            }
            if (targetFolder.exists()) {
                return;
            }

            try {
                Files.move(legacyFolder.toPath(), targetFolder.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("Alter Plugin-Ordner '" + legacyName + "' wurde nach '" + DATA_FOLDER_NAME + "' verschoben.");
            } catch (Exception exception) {
                getLogger().warning("Alten Plugin-Ordner konnte nicht verschieben: " + exception.getMessage());
            }
            return;
        }
    }
}
