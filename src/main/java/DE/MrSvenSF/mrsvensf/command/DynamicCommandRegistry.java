package DE.MrSvenSF.mrsvensf.command;

import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DynamicCommandRegistry {

    private final JavaPlugin plugin;
    private final ConfigSystem configSystem;
    private final CommandExecutor executor;
    private final TabCompleter tabCompleter;
    private final Map<String, Command> registeredCommands = new ConcurrentHashMap<>();

    public DynamicCommandRegistry(
            JavaPlugin plugin,
            ConfigSystem configSystem,
            CommandExecutor executor,
            TabCompleter tabCompleter
    ) {
        this.plugin = plugin;
        this.configSystem = configSystem;
        this.executor = executor;
        this.tabCompleter = tabCompleter;
    }

    public void refresh() {
        CommandMap commandMap = resolveCommandMap();
        if (commandMap == null) {
            plugin.getLogger().warning("Dynamische Commands konnten nicht registriert werden: CommandMap nicht gefunden.");
            return;
        }

        Set<String> desiredCommands = resolveDesiredCommands();
        unregisterRemovedCommands(commandMap, desiredCommands);

        for (String commandName : desiredCommands) {
            if (registeredCommands.containsKey(commandName) || plugin.getCommand(commandName) != null) {
                continue;
            }

            ConfiguredCommand command = new ConfiguredCommand(commandName, executor, tabCompleter);
            commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command);
            registeredCommands.put(commandName, command);
        }
    }

    public void unregisterAll() {
        CommandMap commandMap = resolveCommandMap();
        if (commandMap == null) {
            registeredCommands.clear();
            return;
        }

        List<Command> commands = new ArrayList<>(registeredCommands.values());
        for (Command command : commands) {
            unregisterCommand(commandMap, command);
        }
        registeredCommands.clear();
    }

    private Set<String> resolveDesiredCommands() {
        LinkedHashSet<String> commands = new LinkedHashSet<>();
        commands.addAll(configSystem.getConfiguredModerationRootCommands());
        commands.addAll(configSystem.getConfiguredPlayerRootCommands());

        commands.removeIf(command -> !isValidCommandName(command));
        return commands;
    }

    private void unregisterRemovedCommands(CommandMap commandMap, Set<String> desiredCommands) {
        List<String> removedCommands = registeredCommands.keySet()
                .stream()
                .filter(commandName -> !desiredCommands.contains(commandName))
                .toList();

        for (String commandName : removedCommands) {
            Command command = registeredCommands.remove(commandName);
            if (command != null) {
                unregisterCommand(commandMap, command);
            }
        }
    }

    private void unregisterCommand(CommandMap commandMap, Command command) {
        command.unregister(commandMap);
        removeKnownCommand(commandMap, command);
    }

    private boolean isValidCommandName(String commandName) {
        return commandName != null && commandName.matches("[a-z0-9_-]+");
    }

    private CommandMap resolveCommandMap() {
        try {
            Method method = plugin.getServer().getClass().getMethod("getCommandMap");
            Object result = method.invoke(plugin.getServer());
            return result instanceof CommandMap commandMap ? commandMap : null;
        } catch (Exception exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void removeKnownCommand(CommandMap commandMap, Command command) {
        try {
            Field field = findField(commandMap.getClass(), "knownCommands");
            if (field == null) {
                return;
            }

            field.setAccessible(true);
            Object value = field.get(commandMap);
            if (!(value instanceof Map<?, ?> rawMap)) {
                return;
            }

            Map<String, Command> knownCommands = (Map<String, Command>) rawMap;
            knownCommands.entrySet().removeIf(entry -> entry.getValue() == command);
        } catch (Exception ignored) {
            // Bukkit keeps stale commands in rare implementations; the registered map still stays correct.
        }
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static final class ConfiguredCommand extends Command {

        private final CommandExecutor executor;
        private final TabCompleter tabCompleter;

        private ConfiguredCommand(String name, CommandExecutor executor, TabCompleter tabCompleter) {
            super(name);
            this.executor = executor;
            this.tabCompleter = tabCompleter;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return executor.onCommand(sender, this, commandLabel, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            List<String> completions = tabCompleter.onTabComplete(sender, this, alias, args);
            return completions == null ? List.of() : completions;
        }
    }
}
