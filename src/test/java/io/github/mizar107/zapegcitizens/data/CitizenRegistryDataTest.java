package io.github.mizar107.zapegcitizens.data;

import org.junit.jupiter.api.Test;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CitizenRegistryDataTest {

    @Test
    void reservesNamesCaseInsensitively() {
        CitizenRegistryData data = new CitizenRegistryData();
        UUID citizenId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        data.reserve("Atlas", citizenId, ownerId);

        CitizenRegistryData.CitizenRecord record = data.findByName("atlas").orElseThrow();
        assertEquals("Atlas", record.name());
        assertEquals(citizenId, record.citizenId());
        assertEquals(ownerId, record.ownerId());
        assertThrows(IllegalStateException.class,
                () -> data.reserve("ATLAS", UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void survivesNbtRoundTrip() {
        CitizenRegistryData original = new CitizenRegistryData();
        UUID citizenId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        original.reserve("Miner_1", citizenId, ownerId);

        CitizenRegistryData loaded = CitizenRegistryData.load(original.save(new CompoundTag()));

        CitizenRegistryData.CitizenRecord record = loaded.findByName("MINER_1").orElseThrow();
        assertEquals(citizenId, record.citizenId());
        assertEquals(ownerId, record.ownerId());
    }
}
