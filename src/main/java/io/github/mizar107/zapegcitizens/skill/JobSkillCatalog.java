package io.github.mizar107.zapegcitizens.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Trusted, server-owned workflow guidance for durable citizen jobs.
 *
 * <p>Unlike Numen's client-local skill registry, this catalog is available on a
 * dedicated server and never accepts a resource path from the model. Every
 * public name maps to one fixed resource packaged in the Citizens jar.
 */
public final class JobSkillCatalog {

    public static final String STORAGE = "storage";
    public static final String BUILDING = "building";
    public static final String MINING = "mining";
    public static final String COMBAT = "combat";

    private static final int MAX_SKILL_BYTES = 16 * 1024;
    private static final Map<String, String> RESOURCES = resources();
    private static final List<String> NAMES = List.copyOf(RESOURCES.keySet());

    private JobSkillCatalog() {}

    /** Stable public names used by tool schemas and job planners. */
    public static List<String> names() {
        return NAMES;
    }

    /** Resolve a canonical public name without accepting aliases or file paths. */
    public static Optional<String> find(String name) {
        if (name == null || !RESOURCES.containsKey(name)) {
            return Optional.empty();
        }
        return Optional.of(readResource(name, RESOURCES.get(name)));
    }

    /** Resolve one canonical public name, failing closed for anything else. */
    public static String require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "unknown trusted workflow '" + name + "'; choose one of " + NAMES));
    }

    /** Fail startup/tests early if a packaged workflow is missing or unexpectedly large. */
    public static int verifyResources() {
        for (String name : NAMES) {
            require(name);
        }
        return NAMES.size();
    }

    private static Map<String, String> resources() {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put(STORAGE, "/zapeg_citizens/skills/storage.md");
        resources.put(BUILDING, "/zapeg_citizens/skills/building.md");
        resources.put(MINING, "/zapeg_citizens/skills/mining.md");
        resources.put(COMBAT, "/zapeg_citizens/skills/combat.md");
        return Collections.unmodifiableMap(resources);
    }

    private static String readResource(String name, String path) {
        try (InputStream stream = JobSkillCatalog.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "trusted workflow resource is missing for '" + name + "': " + path);
            }
            byte[] bytes = stream.readNBytes(MAX_SKILL_BYTES + 1);
            if (bytes.length > MAX_SKILL_BYTES) {
                throw new IllegalStateException(
                        "trusted workflow '" + name + "' exceeds " + MAX_SKILL_BYTES + " bytes");
            }
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                throw new IllegalStateException("trusted workflow '" + name + "' is empty");
            }
            return text;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "could not read trusted workflow resource for '" + name + "'", exception);
        }
    }
}
