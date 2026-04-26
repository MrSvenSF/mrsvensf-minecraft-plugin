package DE.MrSvenSF.mrsvensf.command;

import DE.MrSvenSF.mrsvensf.MrSvenSF;
import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import DE.MrSvenSF.mrsvensf.message.MessageService;
import DE.MrSvenSF.mrsvensf.spawn.SpawnService;
import DE.MrSvenSF.mrsvensf.update.UpdateService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MrSvenSFCommand implements CommandExecutor, TabCompleter {

    private final MrSvenSF plugin;
    private final MessageService messageService;
    private final UpdateService updateService;
    private final SpawnService spawnService;

    public MrSvenSFCommand(MrSvenSF plugin, MessageService messageService, UpdateService updateService, SpawnService spawnService) {
        this.plugin = plugin;
        this.messageService = messageService;
        this.updateService = updateService;
        this.spawnService = spawnService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String invokedCommand = resolveInvokedCommandName(command, label);
        if (plugin.getConfigSystem().isPlayerRootCommand(invokedCommand)) {
            ConfigSystem.CommandDefinition playerCommand = plugin.getConfigSystem().resolvePlayerCommand(invokedCommand);
            if (playerCommand == null) {
                messageService.send(sender, "command-disabled");
                return true;
            }

            String playerAction = playerCommand.action() == null ? "" : playerCommand.action().trim().toLowerCase(Locale.ROOT);
            String permission = effectivePermission(
                    playerCommand.permission(),
                    defaultPermissionForPlayerAction(playerAction)
            );
            if (!permission.isBlank() && !sender.hasPermission(permission)) {
                messageService.send(sender, "no-permission");
                return true;
            }
            return handleSpawn(sender);
        }

        if (!plugin.getConfigSystem().isModerationRootCommand(invokedCommand)) {
            messageService.send(sender, "command-disabled");
            return true;
        }

        if (args.length == 0) {
            messageService.sendConfiguredCommands(sender);
            return true;
        }

        String subCommandInput = args[0].toLowerCase(Locale.ROOT);
        ConfigSystem.CommandDefinition moderationCommand = plugin.getConfigSystem().resolveModerationCommand(invokedCommand, subCommandInput);
        if (moderationCommand == null) {
            messageService.sendConfiguredCommands(sender);
            return true;
        }

        String moderationAction = moderationCommand.action() == null
                ? ""
                : moderationCommand.action().trim().toLowerCase(Locale.ROOT);
        if (moderationAction.isBlank()) {
            messageService.sendConfiguredCommands(sender);
            return true;
        }

        String permission = effectivePermission(
                moderationCommand.permission(),
                defaultPermissionForModerationAction(moderationAction)
        );
        if (!permission.isBlank() && !sender.hasPermission(permission)) {
            messageService.send(sender, "no-permission");
            return true;
        }

        return switch (moderationAction) {
            case "reload" -> handleReload(sender);
            case "version" -> handleVersion(sender);
            case "update" -> handleUpdate(sender);
            case "setspawn" -> handleSetSpawn(sender);
            default -> {
                messageService.sendConfiguredCommands(sender);
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        boolean success = plugin.reloadAllConfigs();
        if (success) {
            messageService.send(sender, "reload");
        } else {
            messageService.send(sender, "reload-failed");
        }
        return true;
    }

    private boolean handleVersion(CommandSender sender) {
        updateService.checkLatestVersion().whenComplete((info, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        messageService.send(sender, "update-error", Map.of("error", safeText(throwable.getMessage())));
                        return;
                    }

                    String currentVersion = info.currentVersion();
                    String latestVersion = info.latestVersion();
                    if (!info.success()) {
                        if (latestVersion == null || latestVersion.isBlank()) {
                            latestVersion = updateService.getCachedLatestVersion();
                        }
                        if (latestVersion == null || latestVersion.isBlank()) {
                            latestVersion = currentVersion;
                        }
                    }

                    messageService.send(sender, "version", Map.of(
                            "version", currentVersion,
                            "latest", latestVersion
                    ));

                    if (!info.success()) {
                        messageService.send(sender, "update-error", Map.of("error", info.errorMessage()));
                    }
                })
        );

        return true;
    }

    private boolean handleUpdate(CommandSender sender) {
        updateService.runManualUpdate().whenComplete((result, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        messageService.send(sender, "update-error", Map.of("error", safeText(throwable.getMessage())));
                        return;
                    }

                    if (result.noUpdate()) {
                        messageService.send(sender, "update-no-change", Map.of(
                                "version", result.info().currentVersion(),
                                "latest", result.info().latestVersion()
                        ));
                        return;
                    }

                    if (!result.success()) {
                        String current = result.info() == null ? plugin.getConfigSystem().getConfiguredVersion() : result.info().currentVersion();
                        String latest = result.info() == null ? current : result.info().latestVersion();
                        messageService.send(sender, "update-error", Map.of(
                                "version", current,
                                "latest", latest,
                                "error", safeText(result.errorMessage())
                        ));
                        return;
                    }

                    messageService.send(sender, "update", Map.of(
                            "version", result.info().currentVersion(),
                            "latest", result.info().latestVersion(),
                            "path", result.downloadedPath()
                    ));
                })
        );

        return true;
    }

    private boolean handleSetSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messageService.send(sender, "player-only");
            return true;
        }

        spawnService.setSpawn(player.getLocation());
        messageService.send(sender, "spawn-set", Map.of(
                "world", player.getWorld().getName(),
                "x", formatCoordinate(player.getLocation().getX()),
                "y", formatCoordinate(player.getLocation().getY()),
                "z", formatCoordinate(player.getLocation().getZ())
        ));
        return true;
    }

    private boolean handleSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messageService.send(sender, "player-only");
            return true;
        }
        if (!spawnService.isSpawnFeatureEnabled()) {
            messageService.send(sender, "spawn-disabled");
            return true;
        }

        boolean teleported = spawnService.teleportToSpawn(player);
        if (!teleported) {
            messageService.send(sender, "spawn-not-set");
            return true;
        }

        messageService.send(sender, "spawn-teleported");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String invokedCommand = resolveInvokedCommandName(command, alias);
        if (plugin.getConfigSystem().isPlayerRootCommand(invokedCommand)) {
            return Collections.emptyList();
        }

        if (args.length != 1) {
            return Collections.emptyList();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        return plugin.getConfigSystem().getEnabledCommandDefinitions("moderation")
                .stream()
                .filter(definition -> plugin.getConfigSystem()
                        .getPrimaryCommandName(definition.command())
                        .equalsIgnoreCase(invokedCommand))
                .filter(definition -> {
                    String action = definition.action() == null ? "" : definition.action().trim().toLowerCase(Locale.ROOT);
                    if (action.isBlank()) {
                        return false;
                    }

                    String permission = effectivePermission(
                            definition.permission(),
                            defaultPermissionForModerationAction(action)
                    );
                    return permission.isBlank() || sender.hasPermission(permission);
                })
                .map(ConfigSystem.CommandDefinition::command)
                .map(this::extractModerationSubCommand)
                .filter(option -> !option.isBlank())
                .distinct()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(input))
                .toList();
    }

    private String resolveInvokedCommandName(Command command, String label) {
        String raw = label == null || label.isBlank() ? command.getName() : label;
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int namespaceIndex = normalized.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex < normalized.length() - 1) {
            normalized = normalized.substring(namespaceIndex + 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String defaultPermissionForModerationAction(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "reload" -> "mrsvensf.admin.reload";
            case "version" -> "mrsvensf.admin.version";
            case "update" -> "mrsvensf.admin.update";
            case "setspawn" -> "mrsvensf.admin.setspawn";
            default -> "mrsvensf.admin.use";
        };
    }

    private String defaultPermissionForPlayerAction(String action) {
        if ("spawn".equalsIgnoreCase(action)) {
            return "mrsvensf.player.spawn";
        }
        return "mrsvensf.player.use";
    }

    private String effectivePermission(String configuredPermission, String fallbackPermission) {
        if (configuredPermission == null || configuredPermission.isBlank()) {
            return fallbackPermission == null ? "" : fallbackPermission.trim();
        }
        return configuredPermission.trim();
    }

    private String extractModerationSubCommand(String configuredCommand) {
        if (configuredCommand == null || configuredCommand.isBlank()) {
            return "";
        }

        String normalized = configuredCommand.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        String[] parts = normalized.split("\\s+");
        if (parts.length >= 2) {
            return parts[1].trim();
        }
        return "";
    }

    private String formatCoordinate(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) {
            return "unknown error";
        }
        return value;
    }
}
