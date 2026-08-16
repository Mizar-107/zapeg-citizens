package io.github.mizar107.zapegcitizens.data;

import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistent, server-authoritative index of citizens provisioned by this addon. */
public final class CitizenRegistryData extends SavedData {

    public static final int DATA_VERSION = 3;
    public static final int MAX_PERSONA_LENGTH = 4_096;
    public static final String DEFAULT_ROLE = "worker";
    public static final String DEFAULT_PLAYER_FACTION = "players";
    public static final String DEFAULT_SERVER_FACTION = "server";

    private static final String DATA_NAME = ZapeGCitizens.MOD_ID + "_registry";
    private static final String VERSION_TAG = "Version";
    private static final String CITIZENS_TAG = "Citizens";
    private static final String SERVER_PRINCIPAL_TAG = "ServerPrincipalId";

    private final Map<String, CitizenRecord> records = new LinkedHashMap<>();
    private UUID serverPrincipalId;

    public static CitizenRegistryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                CitizenRegistryData::load, CitizenRegistryData::new, DATA_NAME);
    }

    public Optional<CitizenRecord> findByName(String name) {
        return Optional.ofNullable(records.get(key(name)));
    }

    public Optional<CitizenRecord> findByCitizenId(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId");
        return records.values().stream()
                .filter(record -> record.citizenId().equals(citizenId))
                .findFirst();
    }

    public List<CitizenRecord> all() {
        return List.copyOf(records.values());
    }

    /**
     * Returns the stable, world-specific UUID used as Numen's technical owner for server citizens.
     * It is generated only when server ownership is first used, then persisted in this ledger.
     */
    public UUID serverPrincipalId() {
        if (serverPrincipalId == null) {
            serverPrincipalId = UUID.randomUUID();
            setDirty();
        }
        return serverPrincipalId;
    }

    /** Updates only the operator-authored persona while retaining every identity field. */
    public Optional<CitizenRecord> updatePersona(String name, String persona) {
        String recordKey = key(name);
        CitizenRecord current = records.get(recordKey);
        if (current == null) {
            return Optional.empty();
        }
        CitizenRecord updated = current.withPersona(persona);
        if (!updated.equals(current)) {
            records.put(recordKey, updated);
            setDirty();
        }
        return Optional.of(updated);
    }

    /** Sets or clears a home anchor while retaining every identity and profile field. */
    public Optional<CitizenRecord> updateHome(String name, Optional<HomeAnchor> home) {
        String recordKey = key(name);
        CitizenRecord current = records.get(recordKey);
        if (current == null) {
            return Optional.empty();
        }
        CitizenRecord updated = current.withHome(home);
        if (!updated.equals(current)) {
            records.put(recordKey, updated);
            setDirty();
        }
        return Optional.of(updated);
    }

    /** Convenience overload for assigning a concrete home anchor. */
    public Optional<CitizenRecord> updateHome(String name, HomeAnchor home) {
        return updateHome(name, Optional.of(Objects.requireNonNull(home, "home")));
    }

    /** Releases only the exact row whose body/registry entry was just removed. */
    public boolean remove(CitizenRecord expected) {
        Objects.requireNonNull(expected, "expected");
        boolean removed = records.remove(key(expected.name()), expected);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Returns citizens whose logical owner is the supplied real player. */
    public List<CitizenRecord> ownedBy(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return records.values().stream()
                .filter(record -> record.logicalOwner().matchesPlayer(ownerId))
                .toList();
    }

    /** Reserves a normal player-owned citizen using the current server brain. */
    public void reservePlayer(String name, UUID citizenId, UUID playerId) {
        reserve(new CitizenRecord(
                name,
                citizenId,
                LogicalOwner.player(playerId),
                playerId,
                BrainController.SERVER,
                DEFAULT_ROLE,
                DEFAULT_PLAYER_FACTION,
                "",
                Optional.empty()));
    }

    /**
     * Reserves a server-owned citizen while keeping Numen's required technical body owner
     * explicit and separate from gameplay ownership.
     */
    public void reserveServer(
            String name,
            UUID citizenId,
            String logicalServerId,
            UUID bodyOwnerId,
            String role,
            String faction) {
        reserveServer(
                name,
                citizenId,
                logicalServerId,
                bodyOwnerId,
                new CitizenProfile(role, faction, ""),
                Optional.empty());
    }

    /**
     * Reserves a server-owned citizen with its complete lore profile and required home.
     */
    public void reserveServer(
            String name,
            UUID citizenId,
            String logicalServerId,
            UUID bodyOwnerId,
            CitizenProfile profile,
            HomeAnchor home) {
        reserveServer(
                name,
                citizenId,
                logicalServerId,
                bodyOwnerId,
                profile,
                Optional.of(Objects.requireNonNull(home, "home")));
    }

    /** Convenience overload for callers that already hold the individual profile fields. */
    public void reserveServer(
            String name,
            UUID citizenId,
            String logicalServerId,
            UUID bodyOwnerId,
            String role,
            String faction,
            String persona,
            HomeAnchor home) {
        reserveServer(
                name,
                citizenId,
                logicalServerId,
                bodyOwnerId,
                new CitizenProfile(role, faction, persona),
                home);
    }

    private void reserveServer(
            String name,
            UUID citizenId,
            String logicalServerId,
            UUID bodyOwnerId,
            CitizenProfile profile,
            Optional<HomeAnchor> home) {
        Objects.requireNonNull(profile, "profile");
        reserve(new CitizenRecord(
                name,
                citizenId,
                LogicalOwner.server(logicalServerId),
                bodyOwnerId,
                BrainController.SERVER,
                profile.role(),
                profile.faction(),
                profile.persona(),
                home));
    }

    /** Adds a fully described citizen while enforcing global name and body identity uniqueness. */
    public void reserve(CitizenRecord record) {
        Objects.requireNonNull(record, "record");
        String key = key(record.name());
        if (records.containsKey(key)) {
            throw new IllegalStateException(
                    "Citizen name is already reserved: " + record.name());
        }
        if (findByCitizenId(record.citizenId()).isPresent()) {
            throw new IllegalStateException(
                    "Citizen identity is already reserved: " + record.citizenId());
        }
        records.put(key, record);
        setDirty();
    }

    /** @deprecated Use {@link #reservePlayer(String, UUID, UUID)}. */
    @Deprecated(forRemoval = false)
    public void reserve(String name, UUID citizenId, UUID ownerId) {
        reservePlayer(name, citizenId, ownerId);
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt(VERSION_TAG, DATA_VERSION);
        if (serverPrincipalId != null) {
            root.putUUID(SERVER_PRINCIPAL_TAG, serverPrincipalId);
        }
        ListTag citizens = new ListTag();
        for (CitizenRecord record : records.values()) {
            CompoundTag row = new CompoundTag();
            row.putString("Name", record.name());
            row.putUUID("CitizenId", record.citizenId());
            row.putString("OwnerKind", record.logicalOwner().kind().name());
            row.putString("LogicalOwnerId", record.logicalOwner().id());
            row.putUUID("BodyOwnerId", record.bodyOwnerId());
            row.putString("BrainController", record.brainController().name());
            row.putString("Role", record.role());
            row.putString("Faction", record.faction());
            row.putString("Persona", record.persona());
            record.home().ifPresent(home -> row.put("Home", saveHome(home)));

            // Retain the historical field for rollback/read compatibility. Explicit v2 fields
            // above remain authoritative when logical and technical ownership differ.
            row.putUUID("OwnerId", record.ownerId());
            citizens.add(row);
        }
        root.put(CITIZENS_TAG, citizens);
        return root;
    }

    static CitizenRegistryData load(CompoundTag root) {
        CitizenRegistryData data = new CitizenRegistryData();
        if (root.hasUUID(SERVER_PRINCIPAL_TAG)) {
            data.serverPrincipalId = root.getUUID(SERVER_PRINCIPAL_TAG);
        }
        ListTag citizens = root.getList(CITIZENS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < citizens.size(); index++) {
            CompoundTag row = citizens.getCompound(index);
            CitizenRecord record = loadRow(row);
            if (record == null
                    || data.records.containsKey(key(record.name()))
                    || data.findByCitizenId(record.citizenId()).isPresent()) {
                continue;
            }
            data.records.put(key(record.name()), record);
        }
        int sourceVersion = root.contains(VERSION_TAG, Tag.TAG_INT)
                ? root.getInt(VERSION_TAG)
                : 1;
        if (sourceVersion < DATA_VERSION && !data.records.isEmpty()) {
            data.setDirty();
        }
        return data;
    }

    private static CitizenRecord loadRow(CompoundTag row) {
        if (!row.contains("Name", Tag.TAG_STRING) || !row.hasUUID("CitizenId")) {
            return null;
        }

        try {
            if (row.contains("OwnerKind", Tag.TAG_STRING)) {
                if (!row.contains("LogicalOwnerId", Tag.TAG_STRING)
                        || !row.hasUUID("BodyOwnerId")) {
                    return null;
                }

                LogicalOwner logicalOwner = new LogicalOwner(
                        OwnerKind.valueOf(row.getString("OwnerKind").toUpperCase(Locale.ROOT)),
                        row.getString("LogicalOwnerId"));
                BrainController controller = row.contains("BrainController", Tag.TAG_STRING)
                        ? BrainController.valueOf(
                                row.getString("BrainController").toUpperCase(Locale.ROOT))
                        : BrainController.SERVER;
                String role = row.contains("Role", Tag.TAG_STRING)
                        ? row.getString("Role")
                        : DEFAULT_ROLE;
                String faction = row.contains("Faction", Tag.TAG_STRING)
                        ? row.getString("Faction")
                        : defaultFaction(logicalOwner.kind());
                String persona = loadPersona(row);
                Optional<HomeAnchor> home = loadHome(row);

                return new CitizenRecord(
                        row.getString("Name"),
                        row.getUUID("CitizenId"),
                        logicalOwner,
                        row.getUUID("BodyOwnerId"),
                        controller,
                        role,
                        faction,
                        persona,
                        home);
            }

            if (!row.hasUUID("OwnerId")) {
                return null;
            }
            UUID legacyOwnerId = row.getUUID("OwnerId");
            return new CitizenRecord(
                    row.getString("Name"),
                    row.getUUID("CitizenId"),
                    LogicalOwner.player(legacyOwnerId),
                    legacyOwnerId,
                    BrainController.SERVER,
                    DEFAULT_ROLE,
                    DEFAULT_PLAYER_FACTION,
                    "",
                    Optional.empty());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Invalid or future enum values must not crash world loading.
            return null;
        }
    }

    private static String defaultFaction(OwnerKind ownerKind) {
        return ownerKind == OwnerKind.PLAYER
                ? DEFAULT_PLAYER_FACTION
                : DEFAULT_SERVER_FACTION;
    }

    private static CompoundTag saveHome(HomeAnchor home) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", home.dimension());
        tag.putDouble("X", home.x());
        tag.putDouble("Y", home.y());
        tag.putDouble("Z", home.z());
        tag.putFloat("Yaw", home.yaw());
        tag.putFloat("Pitch", home.pitch());
        return tag;
    }

    private static Optional<HomeAnchor> loadHome(CompoundTag row) {
        if (!row.contains("Home", Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag home = row.getCompound("Home");
        if (!home.contains("Dimension", Tag.TAG_STRING)
                || !home.contains("X", Tag.TAG_ANY_NUMERIC)
                || !home.contains("Y", Tag.TAG_ANY_NUMERIC)
                || !home.contains("Z", Tag.TAG_ANY_NUMERIC)
                || !home.contains("Yaw", Tag.TAG_ANY_NUMERIC)
                || !home.contains("Pitch", Tag.TAG_ANY_NUMERIC)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new HomeAnchor(
                    home.getString("Dimension"),
                    home.getDouble("X"),
                    home.getDouble("Y"),
                    home.getDouble("Z"),
                    home.getFloat("Yaw"),
                    home.getFloat("Pitch")));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // A damaged home must not discard the citizen identity or its inventory mapping.
            return Optional.empty();
        }
    }

    private static String loadPersona(CompoundTag row) {
        if (!row.contains("Persona", Tag.TAG_STRING)) {
            return "";
        }
        String persona = row.getString("Persona").strip();
        return persona.length() <= MAX_PERSONA_LENGTH ? persona : "";
    }

    private static String key(String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }

    public enum OwnerKind {
        PLAYER,
        SERVER
    }

    public enum BrainController {
        SERVER
    }

    /** Immutable, operator-authored behavior and lore profile. */
    public record CitizenProfile(String role, String faction, String persona) {

        public CitizenProfile {
            role = requireText(role, "role");
            faction = requireText(faction, "faction");
            persona = normalizePersona(persona);
        }
    }

    /** Stable world location used to wake or recover a server-owned body. */
    public record HomeAnchor(
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {

        public HomeAnchor {
            String rawDimension = requireText(dimension, "home dimension");
            ResourceLocation parsedDimension = ResourceLocation.tryParse(rawDimension);
            if (parsedDimension == null) {
                throw new IllegalArgumentException("home dimension must be a valid resource location");
            }
            dimension = parsedDimension.toString();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("home coordinates and rotation must be finite");
            }
        }
    }

    /** A gameplay owner identity. It is intentionally not Numen's technical owner UUID. */
    public record LogicalOwner(OwnerKind kind, String id) {

        public LogicalOwner {
            kind = Objects.requireNonNull(kind, "kind");
            id = requireText(id, "logical owner id");
            if (kind == OwnerKind.PLAYER) {
                id = UUID.fromString(id).toString();
            }
        }

        public static LogicalOwner player(UUID playerId) {
            return new LogicalOwner(OwnerKind.PLAYER,
                    Objects.requireNonNull(playerId, "playerId").toString());
        }

        public static LogicalOwner server(String serverId) {
            return new LogicalOwner(OwnerKind.SERVER, serverId);
        }

        public Optional<UUID> playerId() {
            return kind == OwnerKind.PLAYER
                    ? Optional.of(UUID.fromString(id))
                    : Optional.empty();
        }

        public boolean matchesPlayer(UUID playerId) {
            return playerId().filter(playerId::equals).isPresent();
        }
    }

    public record CitizenRecord(
            String name,
            UUID citizenId,
            LogicalOwner logicalOwner,
            UUID bodyOwnerId,
            BrainController brainController,
            String role,
            String faction,
            String persona,
            Optional<HomeAnchor> home) {

        public CitizenRecord {
            name = requireText(name, "name");
            citizenId = Objects.requireNonNull(citizenId, "citizenId");
            logicalOwner = Objects.requireNonNull(logicalOwner, "logicalOwner");
            bodyOwnerId = Objects.requireNonNull(bodyOwnerId, "bodyOwnerId");
            brainController = Objects.requireNonNull(brainController, "brainController");
            role = requireText(role, "role");
            faction = requireText(faction, "faction");
            persona = normalizePersona(persona);
            home = Objects.requireNonNull(home, "home");
            home.ifPresent(value -> Objects.requireNonNull(value, "home value"));
        }

        /** Source-compatible constructor for v2 callers that do not yet supply lore fields. */
        public CitizenRecord(
                String name,
                UUID citizenId,
                LogicalOwner logicalOwner,
                UUID bodyOwnerId,
                BrainController brainController,
                String role,
                String faction) {
            this(
                    name,
                    citizenId,
                    logicalOwner,
                    bodyOwnerId,
                    brainController,
                    role,
                    faction,
                    "",
                    Optional.empty());
        }

        /** Convenience constructor for a record with a concrete home anchor. */
        public CitizenRecord(
                String name,
                UUID citizenId,
                LogicalOwner logicalOwner,
                UUID bodyOwnerId,
                BrainController brainController,
                String role,
                String faction,
                String persona,
                HomeAnchor home) {
            this(
                    name,
                    citizenId,
                    logicalOwner,
                    bodyOwnerId,
                    brainController,
                    role,
                    faction,
                    persona,
                    Optional.of(Objects.requireNonNull(home, "home")));
        }

        public CitizenProfile profile() {
            return new CitizenProfile(role, faction, persona);
        }

        public CitizenRecord withPersona(String updatedPersona) {
            return new CitizenRecord(
                    name,
                    citizenId,
                    logicalOwner,
                    bodyOwnerId,
                    brainController,
                    role,
                    faction,
                    updatedPersona,
                    home);
        }

        public CitizenRecord withHome(Optional<HomeAnchor> updatedHome) {
            return new CitizenRecord(
                    name,
                    citizenId,
                    logicalOwner,
                    bodyOwnerId,
                    brainController,
                    role,
                    faction,
                    persona,
                    updatedHome);
        }

        /**
         * Compatibility accessor for the player-only command/event code during migration.
         * New code must choose logicalOwner or bodyOwnerId explicitly.
         */
        @Deprecated(forRemoval = false)
        public UUID ownerId() {
            return logicalOwner.playerId().orElse(bodyOwnerId);
        }
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static String normalizePersona(String value) {
        String normalized = Objects.requireNonNull(value, "persona").strip();
        if (normalized.length() > MAX_PERSONA_LENGTH) {
            throw new IllegalArgumentException(
                    "persona must contain at most " + MAX_PERSONA_LENGTH + " characters");
        }
        return normalized;
    }
}
