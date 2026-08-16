package io.github.mizar107.zapegcitizens.data;

import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;

/** Persistent, server-authoritative index of citizens provisioned by this addon. */
public final class CitizenRegistryData extends SavedData {

    private static final String DATA_NAME = ZapeGCitizens.MOD_ID + "_registry";
    private static final String CITIZENS_TAG = "Citizens";

    private final Map<String, CitizenRecord> records = new LinkedHashMap<>();

    public static CitizenRegistryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                CitizenRegistryData::load, CitizenRegistryData::new, DATA_NAME);
    }

    public Optional<CitizenRecord> findByName(String name) {
        return Optional.ofNullable(records.get(key(name)));
    }

    public List<CitizenRecord> all() {
        return List.copyOf(records.values());
    }

    public List<CitizenRecord> ownedBy(UUID ownerId) {
        return records.values().stream()
                .filter(record -> record.ownerId().equals(ownerId))
                .toList();
    }

    public void reserve(String name, UUID citizenId, UUID ownerId) {
        String key = key(name);
        if (records.containsKey(key)) {
            throw new IllegalStateException("Citizen name is already reserved: " + name);
        }
        records.put(key, new CitizenRecord(name, citizenId, ownerId));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag citizens = new ListTag();
        for (CitizenRecord record : records.values()) {
            CompoundTag row = new CompoundTag();
            row.putString("Name", record.name());
            row.putUUID("CitizenId", record.citizenId());
            row.putUUID("OwnerId", record.ownerId());
            citizens.add(row);
        }
        root.put(CITIZENS_TAG, citizens);
        return root;
    }

    static CitizenRegistryData load(CompoundTag root) {
        CitizenRegistryData data = new CitizenRegistryData();
        ListTag citizens = root.getList(CITIZENS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < citizens.size(); index++) {
            CompoundTag row = citizens.getCompound(index);
            if (!row.contains("Name", Tag.TAG_STRING)
                    || !row.hasUUID("CitizenId")
                    || !row.hasUUID("OwnerId")) {
                continue;
            }
            CitizenRecord record = new CitizenRecord(
                    row.getString("Name"), row.getUUID("CitizenId"), row.getUUID("OwnerId"));
            data.records.putIfAbsent(key(record.name()), record);
        }
        return data;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public record CitizenRecord(String name, UUID citizenId, UUID ownerId) {}
}
