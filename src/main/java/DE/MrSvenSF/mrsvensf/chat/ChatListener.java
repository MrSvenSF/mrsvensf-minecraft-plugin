package DE.MrSvenSF.mrsvensf.chat;

import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatListener implements Listener {

    private static final Pattern HEX_WRAPPED_RANGE =
            Pattern.compile("<#([A-Fa-f0-9]{6})>(.*?)</#([A-Fa-f0-9]{6})>", Pattern.DOTALL);
    private static final Pattern SIMPLE_HEX_COLOR =
            Pattern.compile("(?i)(?<![\\w<:/])#([A-F0-9]{6})(?![A-F0-9])");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final ConfigSystem configSystem;
    private final boolean placeholderApiEnabled;
    private final boolean luckPermsEnabled;
    private final Set<String> warnedInvalidGroups = ConcurrentHashMap.newKeySet();

    public ChatListener(JavaPlugin plugin, ConfigSystem configSystem) {
        this.plugin = plugin;
        this.configSystem = configSystem;
        this.placeholderApiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.luckPermsEnabled = Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
        syncAdvancementGameRule();
    }

    public void reloadRuntimeState() {
        syncAdvancementGameRule();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (!configSystem.isChatEnabled()) {
            return;
        }

        FileConfiguration chatConfig = configSystem.getChatConfig();
        if (!getToggle(chatConfig, "Join-and-Leave-Messages", true)) {
            return;
        }

        Player player = event.getPlayer();
        String configuredMessage = resolveJoinMessage(chatConfig, player);
        if (configuredMessage == null || configuredMessage.isBlank()) {
            event.joinMessage(null);
            return;
        }

        String resolved = applyPlaceholders(configuredMessage, player, "");
        event.joinMessage(toComponent(resolved));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (!configSystem.isChatEnabled()) {
            return;
        }

        FileConfiguration chatConfig = configSystem.getChatConfig();
        if (!getToggle(chatConfig, "Join-and-Leave-Messages", true)) {
            return;
        }

        String configuredMessage = chatConfig.getString("Join-and-Leave-Messages.Leave.Message");
        if (configuredMessage == null || configuredMessage.isBlank()) {
            event.quitMessage(null);
            return;
        }

        String resolved = applyPlaceholders(configuredMessage, event.getPlayer(), "");
        event.quitMessage(toComponent(resolved));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!configSystem.isChatEnabled()) {
            return;
        }

        FileConfiguration chatConfig = configSystem.getChatConfig();
        if (!isCustomChatEnabled(chatConfig)) {
            return;
        }

        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        Player player = event.getPlayer();

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> handleChatMessage(player, plainMessage));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        FileConfiguration chatConfig = configSystem.getChatConfig();
        Player player = event.getEntity();
        String vanillaDeathText = event.deathMessage() == null
                ? player.getName() + " died."
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());

        boolean showDefaultDeathMessage = readDeathDefaultToggle(chatConfig);
        boolean customEnabled = getToggle(chatConfig, "death messages.custom-messages", false);

        if (!showDefaultDeathMessage && !customEnabled) {
            event.deathMessage(null);
            sendConsoleOnly(vanillaDeathText);
            return;
        }

        if (customEnabled) {
            String format = chatConfig.getString("death messages.custom-messages.Format", "");
            if (!format.isBlank()) {
                String deathText = "";
                if (event.deathMessage() != null) {
                    deathText = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
                }

                String safeDeath = configSystem.isChatMiniMessageEnabled()
                        ? MINI_MESSAGE.escapeTags(deathText)
                        : deathText;

                String resolved = applyPlaceholders(format, player, "")
                        .replace("{death}", safeDeath);
                event.deathMessage(toComponent(resolved));
                return;
            }
        }

        if (!showDefaultDeathMessage) {
            event.deathMessage(null);
            sendConsoleOnly(vanillaDeathText);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        FileConfiguration chatConfig = configSystem.getChatConfig();
        boolean showVanillaAdvancement = readAdvancementDefaultToggle(chatConfig);
        boolean customEnabled = getToggle(chatConfig, "advancements.custom-messages", false);

        if (!showVanillaAdvancement && !customEnabled) {
            setAnnounceAdvancements(false);
            String advancementName = resolveAdvancementName(event.getAdvancement().getKey());
            sendConsoleOnly(event.getPlayer().getName() + " got the advancement " + advancementName);
            return;
        }

        if (customEnabled) {
            syncAdvancementGameRule();
            String format = chatConfig.getString("advancements.custom-messages.Format", "");
            if (!format.isBlank()) {
                String advancementName = resolveAdvancementName(event.getAdvancement().getKey());
                String safeAdvancement = configSystem.isChatMiniMessageEnabled()
                        ? MINI_MESSAGE.escapeTags(advancementName)
                        : advancementName;

                String resolved = applyPlaceholders(format, event.getPlayer(), "")
                        .replace("{advancement}", safeAdvancement);
                Bukkit.broadcast(toComponent(resolved));
            }
            return;
        }

        if (!showVanillaAdvancement) {
            syncAdvancementGameRule();
        } else {
            setAnnounceAdvancements(true);
        }
    }

    private void handleChatMessage(Player player, String message) {
        FileConfiguration chatConfig = configSystem.getChatConfig();

        if (!isCustomChatEnabled(chatConfig)) {
            Bukkit.broadcast(Component.text("<" + player.getName() + "> " + message));
            return;
        }

        String format = resolveChatFormat(chatConfig, player);
        if (format == null || format.isBlank()) {
            Bukkit.broadcast(Component.text("<" + player.getName() + "> " + message));
            return;
        }

        String resolved = applyPlaceholders(format, player, message);
        Bukkit.broadcast(toComponent(resolved));
    }

    private String resolveJoinMessage(FileConfiguration chatConfig, Player player) {
        if (!player.hasPlayedBefore() && getToggle(chatConfig, "Join-and-Leave-Messages.first join message", false)) {
            String firstJoin = chatConfig.getString("Join-and-Leave-Messages.first join message.Message");
            if (firstJoin != null && !firstJoin.isBlank()) {
                return firstJoin;
            }
        }

        return chatConfig.getString("Join-and-Leave-Messages.Join.Message");
    }

    private boolean isCustomChatEnabled(FileConfiguration chatConfig) {
        boolean topLevel = getToggle(chatConfig, "Chat-Messages", true);
        boolean defaultEnabled = isDefaultChatFormatEnabled(chatConfig);
        boolean hasDefaultFormat = hasDefaultChatFormat(chatConfig);
        return topLevel && defaultEnabled && hasDefaultFormat;
    }

    private String resolveChatFormat(FileConfiguration chatConfig, Player player) {
        String groupFormat = resolveGroupChatFormat(chatConfig, player);
        if (groupFormat != null && !groupFormat.isBlank()) {
            return groupFormat;
        }

        if (isDefaultChatFormatEnabled(chatConfig)) {
            String defaultFormat = resolveDefaultChatFormat(chatConfig);
            if (defaultFormat != null && !defaultFormat.isBlank()) {
                return defaultFormat;
            }
        }

        return null;
    }

    private String resolveGroupChatFormat(FileConfiguration chatConfig, Player player) {
        if (!getToggle(chatConfig, "Chat-Messages.groups", false)) {
            return null;
        }

        if (!luckPermsEnabled) {
            warnInvalidGroupOnce("__luckperms_missing", "LuckPerms is required for Chat-Messages.groups but is not enabled.");
            return null;
        }

        ConfigurationSection groupsSection = chatConfig.getConfigurationSection("Chat-Messages.groups");
        if (groupsSection == null) {
            return null;
        }

        List<String> playerGroups = getPlayerLuckPermsGroups(player);
        for (String playerGroup : playerGroups) {
            ConfigurationSection groupSection = getGroupSection(groupsSection, playerGroup);
            if (groupSection == null) {
                continue;
            }

            String format = groupSection.getString("Format", "");
            if (!format.isBlank()) {
                return format;
            }
        }

        for (String configuredGroup : groupsSection.getKeys(false)) {
            if (configuredGroup.equalsIgnoreCase("enabled")) {
                continue;
            }

            ConfigurationSection groupSection = groupsSection.getConfigurationSection(configuredGroup);
            if (groupSection == null) {
                continue;
            }

            String normalizedGroup = normalizeGroupName(configuredGroup);
            if (!luckPermsGroupExists(normalizedGroup)) {
                warnInvalidGroupOnce(
                        normalizedGroup,
                        "LuckPerms group configured in ChatMessageConfig.yml does not exist: " + configuredGroup
                );
            }
        }

        return null;
    }

    private ConfigurationSection getGroupSection(ConfigurationSection groupsSection, String groupName) {
        if (groupsSection == null || groupName == null || groupName.isBlank()) {
            return null;
        }

        for (String configuredGroup : groupsSection.getKeys(false)) {
            if (configuredGroup.equalsIgnoreCase("enabled")) {
                continue;
            }
            if (!configuredGroup.equalsIgnoreCase(groupName)) {
                continue;
            }
            return groupsSection.getConfigurationSection(configuredGroup);
        }
        return null;
    }

    private boolean hasDefaultChatFormat(FileConfiguration chatConfig) {
        String format = resolveDefaultChatFormat(chatConfig);
        return format != null && !format.isBlank();
    }

    private boolean isDefaultChatFormatEnabled(FileConfiguration chatConfig) {
        if (chatConfig.contains("Chat-Messages.default.enabled")) {
            return chatConfig.getBoolean("Chat-Messages.default.enabled");
        }
        return getToggle(chatConfig, "Chat-Messages", true);
    }

    private String resolveDefaultChatFormat(FileConfiguration chatConfig) {
        if (chatConfig.contains("Chat-Messages.default.Format")) {
            return chatConfig.getString("Chat-Messages.default.Format");
        }
        return chatConfig.getString("Chat-Messages.Format");
    }

    private String applyPlaceholders(String value, Player player, String message) {
        String safeMessage = configSystem.isChatMiniMessageEnabled()
                ? MINI_MESSAGE.escapeTags(message)
                : message;

        String resolved = value
                .replace("{prefix}", getPlayerPrefix(player))
                .replace("{group}", getPrimaryGroup(player))
                .replace("{player}", player.getName())
                .replace("{message}", safeMessage);

        if (placeholderApiEnabled) {
            return PlaceholderAPI.setPlaceholders(player, resolved);
        }

        return resolved;
    }

    private String getPrimaryGroup(Player player) {
        User user = getLuckPermsUser(player);
        if (user == null) {
            return "";
        }

        String group = user.getPrimaryGroup();
        return group == null ? "" : group;
    }

    private List<String> getPlayerLuckPermsGroups(Player player) {
        User user = getLuckPermsUser(player);
        if (user == null) {
            return List.of();
        }

        LinkedHashSet<String> groups = new LinkedHashSet<>();
        String primaryGroup = user.getPrimaryGroup();
        if (primaryGroup != null && !primaryGroup.isBlank()) {
            groups.add(normalizeGroupName(primaryGroup));
        }

        QueryOptions queryOptions = getQueryOptions(player);
        if (queryOptions != null) {
            for (Group group : user.getInheritedGroups(queryOptions)) {
                if (group != null && group.getName() != null && !group.getName().isBlank()) {
                    groups.add(normalizeGroupName(group.getName()));
                }
            }
        }

        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            if (node != null && node.getGroupName() != null && !node.getGroupName().isBlank()) {
                groups.add(normalizeGroupName(node.getGroupName()));
            }
        }

        return List.copyOf(groups);
    }

    private boolean luckPermsGroupExists(String groupName) {
        if (!luckPermsEnabled || groupName == null || groupName.isBlank()) {
            return false;
        }

        try {
            return LuckPermsProvider.get().getGroupManager().getGroup(groupName) != null;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private String normalizeGroupName(String groupName) {
        return groupName == null ? "" : groupName.trim().toLowerCase(Locale.ROOT);
    }

    private void warnInvalidGroupOnce(String key, String message) {
        String normalizedKey = key == null || key.isBlank() ? message : key.trim().toLowerCase(Locale.ROOT);
        if (!warnedInvalidGroups.add(normalizedKey)) {
            return;
        }
        Bukkit.getConsoleSender().sendMessage(Component.text("[MrSvenSF] " + message, NamedTextColor.RED));
    }

    private String getPlayerPrefix(Player player) {
        String placeholderApiPrefix = getPrefixFromPlaceholderApi(player);
        if (!placeholderApiPrefix.isBlank()) {
            return placeholderApiPrefix;
        }

        String luckPermsPrefix = getPrefixFromLuckPerms(player);
        if (!luckPermsPrefix.isBlank()) {
            return luckPermsPrefix;
        }

        Scoreboard scoreboard = player.getScoreboard();
        if (scoreboard == null) {
            return "";
        }

        Team team = scoreboard.getEntryTeam(player.getName());
        if (team == null) {
            return "";
        }

        return serializeTeamPrefix(team);
    }

    private String getPrefixFromPlaceholderApi(Player player) {
        if (!placeholderApiEnabled) {
            return "";
        }

        String value = PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%");
        if (value == null || value.isBlank() || value.equalsIgnoreCase("%luckperms_prefix%")) {
            return "";
        }
        return value;
    }

    private String getPrefixFromLuckPerms(Player player) {
        User user = getLuckPermsUser(player);
        if (user == null) {
            return "";
        }

        QueryOptions queryOptions = getQueryOptions(player);
        CachedMetaData metaData = queryOptions == null
                ? user.getCachedData().getMetaData()
                : user.getCachedData().getMetaData(queryOptions);

        String prefix = metaData.getPrefix();
        return prefix == null ? "" : prefix;
    }

    private QueryOptions getQueryOptions(Player player) {
        if (!luckPermsEnabled) {
            return null;
        }

        try {
            QueryOptions queryOptions = LuckPermsProvider.get().getContextManager().getQueryOptions(player);
            if (queryOptions != null) {
                return queryOptions;
            }
            return LuckPermsProvider.get().getContextManager().getStaticQueryOptions();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private User getLuckPermsUser(Player player) {
        if (!luckPermsEnabled) {
            return null;
        }

        try {
            return LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private Component toComponent(String text) {
        if (configSystem.isChatMiniMessageEnabled()) {
            String miniMessageInput = translateSimpleHexColor(text);
            miniMessageInput = normalizeHexRangeSyntax(miniMessageInput);
            if (configSystem.isChatLegacyColorsEnabled()) {
                miniMessageInput = translateLegacyToMiniMessage(miniMessageInput);
            }

            try {
                return MINI_MESSAGE.deserialize(miniMessageInput);
            } catch (Exception ignored) {
                // Fallback below.
            }
        }

        if (configSystem.isChatLegacyColorsEnabled()) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
        }

        return Component.text(text);
    }

    private String translateSimpleHexColor(String input) {
        Matcher matcher = SIMPLE_HEX_COLOR.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement("<#" + matcher.group(1) + ">"));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String normalizeHexRangeSyntax(String input) {
        Matcher matcher = HEX_WRAPPED_RANGE.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = "<gradient:#" + matcher.group(1) + ":#" + matcher.group(3) + ">"
                    + matcher.group(2) + "</gradient>";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String translateLegacyToMiniMessage(String input) {
        StringBuilder output = new StringBuilder(input.length() * 2);

        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if ((current == '&' || current == '\u00A7') && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));

                if (code == 'x') {
                    String hexTag = readLegacyHexColor(input, i);
                    if (hexTag != null) {
                        output.append(hexTag);
                        i += 13;
                        continue;
                    }
                }

                String tag = mapLegacyCodeToMiniMessage(code);
                if (tag != null) {
                    output.append(tag);
                    i++;
                    continue;
                }
            }

            output.append(current);
        }

        return output.toString();
    }

    private String readLegacyHexColor(String input, int startIndex) {
        if (startIndex + 13 >= input.length()) {
            return null;
        }

        StringBuilder hex = new StringBuilder(6);
        int cursor = startIndex + 2;

        for (int i = 0; i < 6; i++) {
            if (cursor + 1 >= input.length()) {
                return null;
            }

            char separator = input.charAt(cursor);
            if (separator != '&' && separator != '\u00A7') {
                return null;
            }

            char digit = Character.toLowerCase(input.charAt(cursor + 1));
            if (!isHexDigit(digit)) {
                return null;
            }

            hex.append(digit);
            cursor += 2;
        }

        return "<#" + hex + ">";
    }

    private boolean isHexDigit(char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f');
    }

    private String mapLegacyCodeToMiniMessage(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

    private String resolveAdvancementName(NamespacedKey key) {
        String raw = key.getKey();
        int slashIndex = raw.lastIndexOf('/');
        String simple = slashIndex >= 0 ? raw.substring(slashIndex + 1) : raw;
        return simple.replace('_', ' ');
    }

    private void syncAdvancementGameRule() {
        FileConfiguration chatConfig = configSystem.getChatConfig();
        boolean showVanillaAdvancement = readAdvancementDefaultToggle(chatConfig);
        boolean customEnabled = getToggle(chatConfig, "advancements.custom-messages", false);
        boolean announce = showVanillaAdvancement && !customEnabled;
        setAnnounceAdvancements(announce);
    }

    private void setAnnounceAdvancements(boolean value) {
        GameRule<Boolean> rule = getBooleanGameRule("announceAdvancements");
        if (rule == null) {
            return;
        }
        Bukkit.getWorlds().forEach(world -> world.setGameRule(rule, value));
    }

    @SuppressWarnings("unchecked")
    private GameRule<Boolean> getBooleanGameRule(String name) {
        GameRule<?> rule = resolveGameRuleByName(name);
        if (rule == null || rule.getType() != Boolean.class) {
            return null;
        }
        return (GameRule<Boolean>) rule;
    }

    private GameRule<?> resolveGameRuleByName(String name) {
        try {
            java.lang.reflect.Method method = GameRule.class.getMethod("getByName", String.class);
            Object result = method.invoke(null, name);
            return result instanceof GameRule<?> rule ? rule : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private void sendConsoleOnly(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        Bukkit.getConsoleSender().sendMessage(
                Component.text("[MrSvenSF] ", NamedTextColor.GRAY).append(Component.text(message))
        );
    }

    private String serializeTeamPrefix(Team team) {
        try {
            java.lang.reflect.Method componentPrefixMethod = Team.class.getMethod("prefix");
            Object result = componentPrefixMethod.invoke(team);
            if (result instanceof Component component) {
                return LegacyComponentSerializer.legacySection().serialize(component);
            }
        } catch (Exception ignored) {
            // Older Bukkit API fallback below.
        }

        try {
            java.lang.reflect.Method legacyPrefixMethod = Team.class.getMethod("getPrefix");
            Object result = legacyPrefixMethod.invoke(team);
            return result instanceof String prefix ? prefix : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean readDeathDefaultToggle(FileConfiguration chatConfig) {
        return getBooleanFromAnyPath(
                chatConfig,
                true,
                "death messages.death-message",
                "death messages.death-messages",
                "death messages.Dev-Messages",
                "death messages.dev-messages"
        );
    }

    private boolean readAdvancementDefaultToggle(FileConfiguration chatConfig) {
        return getBooleanFromAnyPath(
                chatConfig,
                true,
                "advancements.advancement-message",
                "advancements.advancement-messages",
                "advancements.Advancement-Messages",
                "advancements.advancementMessages"
        );
    }

    private boolean getToggle(FileConfiguration chatConfig, String basePath, boolean defaultValue) {
        return getBooleanFromAnyPath(
                chatConfig,
                defaultValue,
                basePath + ".enabled"
        );
    }

    private boolean getBooleanFromAnyPath(FileConfiguration chatConfig, boolean defaultValue, String... paths) {
        for (String path : paths) {
            if (chatConfig.contains(path)) {
                return chatConfig.getBoolean(path);
            }
        }
        return defaultValue;
    }
}
