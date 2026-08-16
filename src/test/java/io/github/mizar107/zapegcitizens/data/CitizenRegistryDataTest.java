package io.github.mizar107.zapegcitizens.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CitizenRegistryDataTest {

    @Test
    void reservesNamesGloballyAndCaseInsensitively() {
        CitizenRegistryData data = new CitizenRegistryData();
        UUID citizenId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        data.reservePlayer("Atlas", citizenId, ownerId);

        CitizenRegistryData.CitizenRecord record = data.findByName("atlas").orElseThrow();
        assertEquals("Atlas", record.name());
        assertEquals(citizenId, record.citizenId());
        assertEquals(CitizenRegistryData.LogicalOwner.player(ownerId), record.logicalOwner());
        assertEquals(ownerId, record.bodyOwnerId());
        assertEquals(CitizenRegistryData.BrainController.SERVER, record.brainController());
        assertEquals("worker", record.role());
        assertEquals("players", record.faction());

        assertThrows(IllegalStateException.class,
                () -> data.reserveServer(
                        "ATLAS",
                        UUID.randomUUID(),
                        "world",
                        UUID.randomUUID(),
                        "guard",
                        "village"));
        assertThrows(IllegalStateException.class,
                () -> data.reservePlayer("Other", citizenId, UUID.randomUUID()));
    }

    @Test
    void survivesNbtRoundTripForPlayerAndServerOwners() {
        CitizenRegistryData original = new CitizenRegistryData();
        UUID playerCitizenId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID serverCitizenId = UUID.randomUUID();
        UUID technicalOwnerId = UUID.randomUUID();
        original.reservePlayer("Miner_1", playerCitizenId, playerId);
        original.reserveServer(
                "Warden", serverCitizenId, "overworld-lore", technicalOwnerId, "boss", "ash-court");

        CompoundTag saved = original.save(new CompoundTag());
        CitizenRegistryData loaded = CitizenRegistryData.load(saved);

        assertEquals(CitizenRegistryData.DATA_VERSION, saved.getInt("Version"));
        CitizenRegistryData.CitizenRecord player =
                loaded.findByName("MINER_1").orElseThrow();
        assertEquals(playerCitizenId, player.citizenId());
        assertEquals(CitizenRegistryData.LogicalOwner.player(playerId), player.logicalOwner());

        CitizenRegistryData.CitizenRecord server =
                loaded.findByCitizenId(serverCitizenId).orElseThrow();
        assertEquals("Warden", server.name());
        assertEquals(CitizenRegistryData.LogicalOwner.server("overworld-lore"),
                server.logicalOwner());
        assertEquals(technicalOwnerId, server.bodyOwnerId());
        assertEquals(CitizenRegistryData.BrainController.SERVER, server.brainController());
        assertEquals("boss", server.role());
        assertEquals("ash-court", server.faction());

        ListTag rows = saved.getList("Citizens", CompoundTag.TAG_COMPOUND);
        assertTrue(rows.getCompound(0).hasUUID("OwnerId"));
        assertTrue(rows.getCompound(1).hasUUID("OwnerId"));
    }

    @Test
    void migratesLegacyRowsToExplicitPlayerOwnership() {
        UUID citizenId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        CompoundTag legacyRow = new CompoundTag();
        legacyRow.putString("Name", "Legacy");
        legacyRow.putUUID("CitizenId", citizenId);
        legacyRow.putUUID("OwnerId", ownerId);
        ListTag rows = new ListTag();
        rows.add(legacyRow);
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("Citizens", rows);

        CitizenRegistryData loaded = CitizenRegistryData.load(legacyRoot);

        CitizenRegistryData.CitizenRecord migrated =
                loaded.findByName("legacy").orElseThrow();
        assertEquals(CitizenRegistryData.LogicalOwner.player(ownerId), migrated.logicalOwner());
        assertEquals(ownerId, migrated.bodyOwnerId());
        assertEquals(CitizenRegistryData.BrainController.SERVER, migrated.brainController());
        assertEquals("worker", migrated.role());
        assertEquals("players", migrated.faction());
        assertTrue(loaded.isDirty());

        CompoundTag upgraded = loaded.save(new CompoundTag());
        CompoundTag upgradedRow = upgraded.getList("Citizens", CompoundTag.TAG_COMPOUND)
                .getCompound(0);
        assertEquals("PLAYER", upgradedRow.getString("OwnerKind"));
        assertEquals(ownerId.toString(), upgradedRow.getString("LogicalOwnerId"));
        assertEquals(ownerId, upgradedRow.getUUID("BodyOwnerId"));
    }

    @Test
    void ownedByUsesLogicalPlayerRatherThanTechnicalBodyOwner() {
        CitizenRegistryData data = new CitizenRegistryData();
        UUID playerId = UUID.randomUUID();
        UUID sharedTechnicalOwner = UUID.randomUUID();
        UUID playerCitizenId = UUID.randomUUID();
        UUID serverCitizenId = UUID.randomUUID();
        data.reserve(new CitizenRegistryData.CitizenRecord(
                "PlayerCitizen",
                playerCitizenId,
                CitizenRegistryData.LogicalOwner.player(playerId),
                sharedTechnicalOwner,
                CitizenRegistryData.BrainController.SERVER,
                "worker",
                "players"));
        data.reserveServer(
                "LoreCitizen",
                serverCitizenId,
                "world",
                playerId,
                "herald",
                "neutral");

        assertEquals(1, data.ownedBy(playerId).size());
        assertEquals(playerCitizenId, data.ownedBy(playerId).get(0).citizenId());
        assertTrue(data.ownedBy(sharedTechnicalOwner).isEmpty());
        assertEquals("LoreCitizen", data.findByCitizenId(serverCitizenId).orElseThrow().name());
    }

    @Test
    void removesReservationCaseInsensitively() {
        CitizenRegistryData data = new CitizenRegistryData();
        UUID citizenId = UUID.randomUUID();
        data.reservePlayer("Atlas", citizenId, UUID.randomUUID());

        CitizenRegistryData.CitizenRecord removed = data.findByName("aTlAs").orElseThrow();

        assertEquals(citizenId, removed.citizenId());
        assertTrue(data.remove(removed));
        assertTrue(data.findByName("Atlas").isEmpty());
        assertTrue(data.findByCitizenId(citizenId).isEmpty());
        assertTrue(data.isDirty());
        assertTrue(!data.remove(removed));
    }

    @Test
    void refusesToRemoveAReplacementRecord() {
        CitizenRegistryData data = new CitizenRegistryData();
        UUID ownerId = UUID.randomUUID();
        CitizenRegistryData.CitizenRecord expected = new CitizenRegistryData.CitizenRecord(
                "Atlas",
                UUID.randomUUID(),
                CitizenRegistryData.LogicalOwner.player(ownerId),
                ownerId,
                CitizenRegistryData.BrainController.SERVER,
                "worker",
                "players");
        CitizenRegistryData.CitizenRecord replacement = new CitizenRegistryData.CitizenRecord(
                "Atlas",
                UUID.randomUUID(),
                CitizenRegistryData.LogicalOwner.player(ownerId),
                ownerId,
                CitizenRegistryData.BrainController.SERVER,
                "worker",
                "players");
        data.reserve(replacement);

        assertTrue(!data.remove(expected));
        assertEquals(replacement, data.findByName("atlas").orElseThrow());
    }
}
