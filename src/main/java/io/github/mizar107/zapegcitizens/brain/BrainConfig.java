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
        int maxToolSteps) {

    private static final String URL_ENV = "CITIZENS_BRAIN_URL";
    private static final String TOKEN_ENV = "CITIZENS_BRAIN_TOKEN";
    private static final String TOKEN_FILE_ENV = "CITIZENS_BRAIN_TOKEN_FILE";

    public BrainConfig {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(turnTimeout, "turnTimeout");
        if (token.isBlank()) {
            throw new IllegalStateException("The shared brain bearer token must not be blank");
        }
        for (int index = 0; index < token.length(); index++) {
            if (Character.isISOControl(token.charAt(index))) {
                throw new IllegalStateException(
                        "The shared brain bearer token must not contain control characters");
            }
        }
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
                boundedInt("CITIZENS_BRAIN_MAX_TOOL_STEPS", 16, 1, 64)));
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
