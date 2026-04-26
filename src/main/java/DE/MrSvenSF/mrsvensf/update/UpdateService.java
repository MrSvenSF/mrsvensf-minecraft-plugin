package DE.MrSvenSF.mrsvensf.update;

import DE.MrSvenSF.mrsvensf.config.ConfigSystem;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateService {

    private static final String GITHUB_RELEASES_URL = "https://github.com/MrSvenSF/mrsvensf-minecraft-plugin/releases";
    private static final String GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/MrSvenSF/mrsvensf-minecraft-plugin/releases/latest";

    private static final long DAILY_TICKS = 20L * 60L * 60L * 24L;
    private static final Pattern JSON_TAG_NAME_PATTERN =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_DOWNLOAD_URL_PATTERN =
            Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");

    private final JavaPlugin plugin;
    private final ConfigSystem configSystem;
    private final HttpClient httpClient;
    private final AtomicBoolean autoUpdateRunning = new AtomicBoolean(false);

    private BukkitTask autoUpdateTask;
    private volatile String cachedLatestVersion;

    public UpdateService(JavaPlugin plugin, ConfigSystem configSystem) {
        this.plugin = plugin;
        this.configSystem = configSystem;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void start() {
        restartAutoUpdater();
    }

    public void stop() {
        if (autoUpdateTask != null) {
            autoUpdateTask.cancel();
            autoUpdateTask = null;
        }
    }

    public void restartAutoUpdater() {
        stop();

        if (!configSystem.isUpdateEnabled() || !configSystem.isAutoUpdateEnabled()) {
            return;
        }

        autoUpdateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runAutoUpdate, 20L * 15L, DAILY_TICKS);
    }

    public CompletableFuture<UpdateInfo> checkLatestVersion() {
        String current = currentConfiguredVersion();
        if (!configSystem.isUpdateEnabled()) {
            return CompletableFuture.completedFuture(new UpdateInfo(current, current, false, "", true, "Updates disabled."));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(GITHUB_LATEST_RELEASE_API))
                        .GET()
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "mrsvensf-plugin-updater")
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return new UpdateInfo(
                            current,
                            current,
                            false,
                            "",
                            false,
                            "Version check failed with HTTP " + response.statusCode() + " (" + GITHUB_RELEASES_URL + ")"
                    );
                }

                ParsedRemote parsedRemote = parseRemotePayload(response.body());
                if (parsedRemote.version().isBlank()) {
                    return new UpdateInfo(current, current, false, "", false, "Latest release tag is empty.");
                }
                if (parsedRemote.downloadUrl().isBlank()) {
                    return new UpdateInfo(
                            current,
                            parsedRemote.version(),
                            false,
                            "",
                            false,
                            "No .jar asset found in latest GitHub release."
                    );
                }

                String latest = parsedRemote.version().trim();
                this.cachedLatestVersion = latest;
                boolean updateAvailable = isRemoteNewer(current, latest);

                return new UpdateInfo(
                        current,
                        latest,
                        updateAvailable,
                        parsedRemote.downloadUrl(),
                        true,
                        ""
                );
            } catch (Exception exception) {
                return new UpdateInfo(
                        current,
                        current,
                        false,
                        "",
                        false,
                        "Version check failed: " + exception.getMessage()
                );
            }
        });
    }

    public CompletableFuture<UpdateResult> runManualUpdate() {
        return checkLatestVersion().thenCompose(info -> {
            if (!info.success()) {
                return CompletableFuture.completedFuture(UpdateResult.failed(info, info.errorMessage()));
            }

            if (!info.updateAvailable()) {
                return CompletableFuture.completedFuture(UpdateResult.noUpdate(info));
            }

            if (info.downloadUrl().isBlank()) {
                return CompletableFuture.completedFuture(UpdateResult.failed(info, "No download URL configured."));
            }

            return CompletableFuture.supplyAsync(() -> downloadUpdate(info));
        });
    }

    public String getCachedLatestVersion() {
        return cachedLatestVersion;
    }

    private void runAutoUpdate() {
        if (!autoUpdateRunning.compareAndSet(false, true)) {
            return;
        }

        runManualUpdate().whenComplete((result, throwable) -> {
            try {
                if (throwable != null) {
                    plugin.getLogger().warning("Auto-Update fehlgeschlagen: " + throwable.getMessage());
                    return;
                }

                if (!result.success()) {
                    if (!result.noUpdate()) {
                        plugin.getLogger().warning("Auto-Update fehlgeschlagen: " + result.errorMessage());
                    }
                    return;
                }

                plugin.getLogger().info(
                        "Update heruntergeladen: " + result.info().latestVersion()
                                + " (wird beim naechsten Serverstart aktiv)."
                );
            } finally {
                autoUpdateRunning.set(false);
            }
        });
    }

    private UpdateResult downloadUpdate(UpdateInfo info) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(info.downloadUrl()))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return UpdateResult.failed(info, "Download failed with HTTP " + response.statusCode());
            }

            Path targetPath = resolveCurrentJarPath();
            Path pluginDirectory = targetPath.getParent();
            if (pluginDirectory == null || !Files.exists(pluginDirectory)) {
                return UpdateResult.failed(info, "Could not resolve plugins directory.");
            }

            String targetJarName = targetPath.getFileName() == null ? plugin.getName() + ".jar" : targetPath.getFileName().toString();
            Path tempPath = pluginDirectory.resolve(targetJarName + ".download");

            Files.write(tempPath, response.body());
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            configSystem.setConfiguredVersion(info.latestVersion());
            return UpdateResult.success(info, targetPath.toString());
        } catch (Exception exception) {
            return UpdateResult.failed(info, "Download failed: " + exception.getMessage());
        }
    }

    private Path resolveCurrentJarPath() {
        try {
            URI location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            Path currentJar = Path.of(location);
            String fileName = currentJar.getFileName() == null ? "" : currentJar.getFileName().toString();
            if (!fileName.isBlank() && fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return currentJar;
            }
        } catch (Exception ignored) {
            // Fallback below.
        }

        File pluginsDirectory = plugin.getDataFolder().getParentFile();
        if (pluginsDirectory == null) {
            return Path.of(plugin.getName() + ".jar").toAbsolutePath();
        }
        return pluginsDirectory.toPath().resolve(plugin.getName() + ".jar").toAbsolutePath();
    }

    private String currentConfiguredVersion() {
        String configured = configSystem.getConfiguredVersion();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return plugin.getPluginMeta().getVersion();
    }

    private ParsedRemote parseRemotePayload(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return new ParsedRemote("", "");
        }

        String version = unescapeJsonSlashes(extractByPattern(trimmed, JSON_TAG_NAME_PATTERN));

        String jarDownload = "";
        Matcher urlMatcher = JSON_DOWNLOAD_URL_PATTERN.matcher(trimmed);
        while (urlMatcher.find()) {
            String candidate = unescapeJsonSlashes(urlMatcher.group(1).trim());
            if (candidate.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                jarDownload = candidate;
                break;
            }
        }

        return new ParsedRemote(version, jarDownload);
    }

    private String extractByPattern(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String unescapeJsonSlashes(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input.replace("\\/", "/").trim();
    }

    private boolean isRemoteNewer(String currentVersion, String latestVersion) {
        String current = sanitizeVersion(currentVersion);
        String latest = sanitizeVersion(latestVersion);

        String[] currentParts = current.split("\\.");
        String[] latestParts = latest.split("\\.");
        int max = Math.max(currentParts.length, latestParts.length);

        for (int i = 0; i < max; i++) {
            int currentPart = parseVersionPart(currentParts, i);
            int latestPart = parseVersionPart(latestParts, i);

            if (latestPart > currentPart) {
                return true;
            }
            if (latestPart < currentPart) {
                return false;
            }
        }

        return !latest.equalsIgnoreCase(current) && latest.compareToIgnoreCase(current) > 0;
    }

    private int parseVersionPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }

        String value = parts[index].replaceAll("[^0-9]", "");
        if (value.isEmpty()) {
            return 0;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String sanitizeVersion(String value) {
        if (value == null || value.isBlank()) {
            return "0";
        }
        return value.trim();
    }

    private record ParsedRemote(String version, String downloadUrl) {
    }

    public record UpdateInfo(
            String currentVersion,
            String latestVersion,
            boolean updateAvailable,
            String downloadUrl,
            boolean success,
            String errorMessage
    ) {
    }

    public record UpdateResult(boolean success, boolean noUpdate, UpdateInfo info, String errorMessage, String downloadedPath) {

        public static UpdateResult success(UpdateInfo info, String downloadedPath) {
            return new UpdateResult(true, false, info, "", downloadedPath);
        }

        public static UpdateResult noUpdate(UpdateInfo info) {
            return new UpdateResult(false, true, info, "", "");
        }

        public static UpdateResult failed(UpdateInfo info, String errorMessage) {
            return new UpdateResult(false, false, info, errorMessage, "");
        }
    }
}
