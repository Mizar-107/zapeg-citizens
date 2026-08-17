package io.github.mizar107.zapegcitizens.brain;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Host-only configuration for the shared brain bridge. */
public record BrainConfig(
        URI baseUri,
        String token,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration turnTimeout,
        int maxToolSteps,
        int maxJobActions,
        int maxJobModelCalls,
        Duration maxJobActiveTime) {

    private static final String URL_ENV = "CITIZENS_BRAIN_URL";
    private static final String TOKEN_ENV = "CITIZENS_BRAIN_TOKEN";
    private static final String TOKEN_FILE_ENV = "CITIZENS_BRAIN_TOKEN_FILE";

    public BrainConfig {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(turnTimeout, "turnTimeout");
        Objects.requireNonNull(maxJobActiveTime, "maxJobActiveTime");
        if (token.isBlank()) {
            throw new IllegalStateException("The shared brain bearer token must not be blank");
        }
        for (int index = 0; index < token.length(); index++) {
            if (Character.isISOControl(token.charAt(index))) {
                throw new IllegalStateException(
                        "The shared brain bearer token must not contain control characters");
            }
        }
        if (maxToolSteps < 1 || maxToolSteps > 64) {
            throw new IllegalStateException("maxToolSteps must be between 1 and 64");
        }
        if (maxJobActions < 1 || maxJobActions > 4_096) {
            throw new IllegalStateException("maxJobActions must be between 1 and 4096");
        }
        if (maxJobModelCalls < 1 || maxJobModelCalls > 8_192) {
            throw new IllegalStateException("maxJobModelCalls must be between 1 and 8192");
        }
        if (maxJobActiveTime.compareTo(Duration.ofSeconds(30)) < 0
                || maxJobActiveTime.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalStateException("maxJobActiveTime must be between 30 seconds and 30 days");
        }
    }

    /** Source-compatible constructor retained for the dialogue-only tests and callers. */
    public BrainConfig(
            URI baseUri,
            String token,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration turnTimeout,
            int maxToolSteps) {
        this(
                baseUri,
                token,
                connectTimeout,
                requestTimeout,
                turnTimeout,
                maxToolSteps,
                128,
                192,
                Duration.ofHours(3));
    }

    public static Optional<BrainConfig> fromEnvironment() {
        String configuredUrl = value(URL_ENV).orElse("").strip();
        if (configuredUrl.isEmpty()) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = URI.create(configuredUrl.endsWith("/")
                    ? configuredUrl.substring(0, configuredUrl.length() - 1)
                    : configuredUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(URL_ENV + " is not a valid URL", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(URL_ENV + " must use http or https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || uri.getPort() > 65_535) {
            throw new IllegalStateException(
                    URL_ENV + " must be an origin URL without credentials, query, or fragment");
        }

        String token = secret(TOKEN_ENV, TOKEN_FILE_ENV);
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "A shared brain URL requires " + TOKEN_ENV + " or " + TOKEN_FILE_ENV);
        }

        return Optional.of(new BrainConfig(
                uri,
                token,
                Duration.ofMillis(boundedInt("CITIZENS_BRAIN_CONNECT_TIMEOUT_MS", 3_000, 250, 30_000)),
                Duration.ofMillis(boundedInt("CITIZENS_BRAIN_REQUEST_TIMEOUT_MS", 150_000, 1_000, 600_000)),
                Duration.ofMillis(boundedInt("CITIZENS_BRAIN_TURN_TIMEOUT_MS", 600_000, 30_000, 86_400_000)),
                boundedInt("CITIZENS_BRAIN_MAX_TOOL_STEPS", 16, 1, 64),
                boundedInt("CITIZENS_JOB_MAX_ACTIONS", 128, 1, 4_096),
                boundedInt("CITIZENS_JOB_MAX_MODEL_CALLS", 192, 1, 8_192),
                Duration.ofSeconds(boundedInt(
                        "CITIZENS_JOB_MAX_ACTIVE_SECONDS", 10_800, 30, 2_592_000))));
    }

    URI endpoint(String path) {
        return baseUri.resolve(path.startsWith("/") ? path : "/" + path);
    }

    private static String secret(String valueName, String fileName) {
        Optional<String> configuredDirect = value(valueName);
        Optional<String> configuredFile = value(fileName);
        if (configuredDirect.isPresent() && configuredFile.isPresent()) {
            throw new IllegalStateException("Set only one of " + valueName + " or " + fileName);
        }
        Optional<String> direct = configuredDirect.map(String::strip).filter(text -> !text.isEmpty());
        if (direct.isPresent()) {
            return direct.orElseThrow();
        }
        Optional<String> file = configuredFile.map(String::strip).filter(text -> !text.isEmpty());
        if (file.isEmpty()) {
            return "";
        }
        try {
            if (Files.size(Path.of(file.orElseThrow())) > 65_536L) {
                throw new IllegalStateException(fileName + " is unexpectedly large");
            }
            return Files.readString(Path.of(file.orElseThrow())).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read secret file configured by " + fileName,
                    exception);
        }
    }

    private static int boundedInt(String name, int fallback, int minimum, int maximum) {
        String raw = value(name).orElse("").strip();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalStateException(
                        name + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be an integer", exception);
        }
    }

    private static Optional<String> value(String name) {
        String property = System.getProperty(name);
        return property != null ? Optional.of(property) : Optional.ofNullable(System.getenv(name));
    }
}
