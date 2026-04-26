package DE.MrSvenSF.mrsvensf.message;

import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageService {

    private static final Pattern COLOR_BLOCK_PATTERN =
            Pattern.compile("\\{(Prime-Colors-One|Prime-Colors-Two)}(.*?)\\{/\\1}", Pattern.DOTALL);
    private static final Pattern HEX_WRAPPED_RANGE =
            Pattern.compile("<#([A-Fa-f0-9]{6})>(.*?)</#([A-Fa-f0-9]{6})>", Pattern.DOTALL);
    private static final Pattern SIMPLE_HEX_COLOR =
            Pattern.compile("(?i)(?<![\\w<:/])#([A-F0-9]{6})(?![A-F0-9])");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ConfigSystem configSystem;

    public MessageService(ConfigSystem configSystem) {
        this.configSystem = configSystem;
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        List<String> lines = getLines(key);
        if (lines.isEmpty()) {
            return;
        }

        Map<String, String> safePlaceholders = placeholders == null ? Map.of() : placeholders;
        for (String line : lines) {
            String rendered = renderText(line, safePlaceholders);
            sender.sendMessage(toComponent(rendered));
        }
    }

    public void sendConfiguredCommands(CommandSender sender) {
        List<String> commands = new ArrayList<>();

        for (ConfigSystem.CommandDefinition definition : configSystem.getEnabledCommandDefinitions("moderation")) {
            String action = definition.action() == null ? "" : definition.action().trim().toLowerCase(Locale.ROOT);
            if (action.isBlank()) {
                continue;
            }

            String permission = effectivePermission(definition.permission(), defaultPermissionForModerationAction(action));
            if (!permission.isBlank() && !sender.hasPermission(permission)) {
                continue;
            }

            String command = definition.command();
            if (command == null || command.isBlank()) {
                continue;
            }
            commands.add(command.trim());
        }

        for (ConfigSystem.CommandDefinition definition : configSystem.getEnabledCommandDefinitions("player")) {
            String action = definition.action() == null ? "" : definition.action().trim().toLowerCase(Locale.ROOT);
            String permission = effectivePermission(definition.permission(), defaultPermissionForPlayerAction(action));
            if (!permission.isBlank() && !sender.hasPermission(permission)) {
                continue;
            }

            String command = definition.command();
            if (command == null || command.isBlank()) {
                continue;
            }
            commands.add(command.trim());
        }

        commands = List.copyOf(new LinkedHashSet<>(commands));

        if (commands.isEmpty()) {
            send(sender, "command-disabled");
            return;
        }

        for (String command : commands) {
            String value = command == null ? "" : command.trim();
            if (value.isEmpty()) {
                continue;
            }
            sender.sendMessage(toComponent(applyPrimeColors("{Prime-Colors-One}" + value + "{/Prime-Colors-One}")));
        }
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

    public String renderText(String input, Map<String, String> placeholders) {
        String resolved = replacePlaceholders(input, placeholders);
        return applyPrimeColors(resolved);
    }

    private List<String> getLines(String path) {
        if (configSystem.getMessageConfig().isList(path)) {
            return configSystem.getMessageConfig().getStringList(path);
        }

        if (configSystem.getMessageConfig().contains(path)) {
            String single = configSystem.getMessageConfig().getString(path, "");
            if (!single.isBlank()) {
                return List.of(single);
            }
        }

        return List.of();
    }

    private String replacePlaceholders(String value, Map<String, String> placeholders) {
        String resolved = value == null ? "" : value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String token = "{" + entry.getKey() + "}";
            String replacement = entry.getValue() == null ? "" : entry.getValue();
            resolved = resolved.replace(token, replacement);
        }
        return resolved;
    }

    private String applyPrimeColors(String value) {
        Matcher matcher = COLOR_BLOCK_PATTERN.matcher(value);
        StringBuffer output = new StringBuffer();

        while (matcher.find()) {
            String key = matcher.group(1);
            String content = matcher.group(2);
            String template = resolveColorTemplate(key);
            String replacement = applyColorTemplate(template, content);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);

        String result = output.toString();
        result = result.replace("{Prime-Colors-One}", "").replace("{/Prime-Colors-One}", "");
        result = result.replace("{Prime-Colors-Two}", "").replace("{/Prime-Colors-Two}", "");
        return result;
    }

    private String resolveColorTemplate(String key) {
        if ("Prime-Colors-One".equalsIgnoreCase(key)) {
            return configSystem.getPrimeColorOne();
        }
        return configSystem.getPrimeColorTwo();
    }

    private String applyColorTemplate(String template, String content) {
        String safeTemplate = template == null || template.isBlank() ? "{message}" : template;
        String safeContent = content == null ? "" : content;

        if (safeTemplate.contains("{message}")) {
            return safeTemplate.replace("{message}", safeContent);
        }

        return safeTemplate + safeContent;
    }

    private Component toComponent(String text) {
        if (configSystem.isColorsMiniMessageEnabled()) {
            String miniMessageInput = translateSimpleHexColor(text);
            miniMessageInput = normalizeHexRangeSyntax(miniMessageInput);
            if (configSystem.isColorsLegacyColorsEnabled()) {
                miniMessageInput = translateLegacyToMiniMessage(miniMessageInput);
            }

            try {
                return MINI_MESSAGE.deserialize(miniMessageInput);
            } catch (Exception ignored) {
                // Fallback below.
            }
        }

        if (configSystem.isColorsLegacyColorsEnabled()) {
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
}
