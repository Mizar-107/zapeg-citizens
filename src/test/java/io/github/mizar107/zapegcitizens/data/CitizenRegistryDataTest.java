package io.github.mizar107.zapegcitizens.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CitizenRegistryDataTest {

    @Test
    void serverPrincipalIsLazyWorldSpecificAndPersistent() {
        CitizenRegistryData firstWorld = new CitizenRegistryData();
        CitizenRegistryData secondWorld = new CitizenRegistryData();
        assertFalse(firstWorld.isDirty());

        UUID principal = firstWorld.serverPrincipalId();

        assertTrue(firstWorld.isDirty());
        assertEquals(principal, firstWorld.serverPrincipalId());
        assertFalse(principal.equals(secondWorld.serverPrincipalId()));

        CompoundTag saved = firstWorld.save(new CompoundTag());
        assertTrue(saved.hasUUID("ServerPrincipalId"));
        assertEquals(principal, saved.getUUID("ServerPrincipalId"));

        CitizenRegistryData loaded = CitizenRegistryData.load(saved);
        assertEquals(principal, loaded.serverPrincipalId());
    }

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
        assertEquals("", record.persona());
        assertTrue(record.home().isEmpty());

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
        CitizenRegistryData.CitizenProfile profile = new CitizenRegistryData.CitizenProfile(
                "boss",
                "ash-court",
                "An ancient warden who protects the ruined gate.");
        CitizenRegistryData.HomeAnchor home = new CitizenRegistryData.HomeAnchor(
                "minecraft:the_nether", 12.25D, 71.0D, -8.5D, 135.0F, -12.5F);
        original.reservePlayer("Miner_1", playerCitizenId, playerId);
        original.reserveServer(
                "Warden",
                serverCitizenId,
                "overworld-lore",
                technicalOwnerId,
                profile,
                home);

        CompoundTag saved = original.save(new CompoundTag());
        CitizenRegistryData loaded = CitizenRegistryData.load(saved);

        assertEquals(CitizenRegistryData.DATA_VERSION, saved.getInt("Version"));
        CitizenRegistryData.CitizenRecord player =
                loaded.findByName("MINER_1").orElseThrow();
        assertEquals(playerCitizenId, player.citizenId());
        assertEquals(CitizenRegistryData.LogicalOwner.player(playerId), player.logicalOwner());
        assertEquals("", player.persona());
        assertTrue(player.home().isEmpty());

        CitizenRegistryData.CitizenRecord server =
                loaded.findByCitizenId(serverCitizenId).orElseThrow();
        assertEquals("Warden", server.name());
        assertEquals(CitizenRegistryData.LogicalOwner.server("overworld-lore"),
                server.logicalOwner());
        assertEquals(technicalOwnerId, server.bodyOwnerId());
        assertEquals(CitizenRegistryData.BrainController.SERVER, server.brainController());
        assertEquals("boss", server.role());
        assertEquals("ash-court", server.faction());
        assertEquals(profile, server.profile());
        assertEquals("An ancient warden who protects the ruined gate.", server.persona());
        assertEquals(home, server.home().orElseThrow());

        ListTag rows = saved.getList("Citizens", CompoundTag.TAG_COMPOUND);
        assertTrue(rows.getCompound(0).hasUUID("OwnerId"));
        assertTrue(rows.getCompound(1).hasUUID("OwnerId"));
        CompoundTag savedServer = rows.getCompound(1);
        assertEquals(profile.persona(), savedServer.getString("Persona"));
        CompoundTag savedHome = savedServer.getCompound("Home");
        assertEquals(home.dimension(), savedHome.getString("Dimension"));
        assertEquals(home.x(), savedHome.getDouble("X"));
        assertEquals(home.y(), savedHome.getDouble("Y"));
        assertEquals(home.z(), savedHome.getDouble("Z"));
        assertEquals(home.yaw(), savedHome.getFloat("Yaw"));
        assertEquals(home.pitch(), savedHome.getFloat("Pitch"));
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
        assertEquals("", migrated.persona());
        assertTrue(migrated.home().isEmpty());
        assertTrue(loaded.isDirty());

        CompoundTag upgraded = loaded.save(new CompoundTag());
        CompoundTag upgradedRow = upgraded.getList("Citizens", CompoundTag.TAG_COMPOUND)
                .getCompound(0);
        assertEquals("PLAYER", upgradedRow.getString("OwnerKind"));
        assertEquals(ownerId.toString(), upgradedRow.getString("LogicalOwnerId"));
        assertEquals(ownerId, upgradedRow.getUUID("BodyOwnerId"));
        assertEquals("", upgradedRow.getString("Persona"));
        assertFalse(upgradedRow.contains("Home"));
    }

    @Test
    void migratesVersionTwoRowsWithoutInventingPersonaOrHome() {
        UUID citizenId = UUID.randomUUID();
        UUID bodyOwnerId = UUID.randomUUID();
        CompoundTag row = new CompoundTag();
        row.putString("Name", "Archivist");
        row.putUUID("CitizenId", citizenId);
        row.putString("OwnerKind", "SERVER");
        row.putString("LogicalOwnerId", "world");
        row.putUUID("BodyOwnerId", bodyOwnerId);
        row.putString("BrainController", "SERVER");
        row.putString("Role", "lore");
        row.putString("Faction", "village");
        row.putUUID("OwnerId", bodyOwnerId);
        ListTag rows = new ListTag();
        rows.add(row);
        CompoundTag versionTwo = new CompoundTag();
        versionTwo.putInt("Version", 2);
        versionTwo.put("Citizens", rows);

        CitizenRegistryData loaded = CitizenRegistryData.load(versionTwo);

        CitizenRegistryData.CitizenRecord migrated =
                loaded.findByCitizenId(citizenId).orElseThrow();
        assertEquals(CitizenRegistryData.LogicalOwner.server("world"), migrated.logicalOwner());
        assertEquals(bodyOwnerId, migrated.bodyOwnerId());
        assertEquals("lore", migrated.role());
        assertEquals("village", migrated.faction());
        assertEquals("", migrated.persona());
        assertTrue(migrated.home().isEmpty());
        assertTrue(loaded.isDirty());
    }

    @Test
    void updatesPersonaAndHomeWithoutChangingIdentityOrProfile() {
        CitizenRegistryData data = new CitizenRegistryData();
        UUID citizenId = UUID.randomUUID();
        UUID bodyOwnerId = UUID.randomUUID();
        CitizenRegistryData.HomeAnchor firstHome = new CitizenRegistryData.HomeAnchor(
                "minecraft:overworld", 1.0D, 64.0D, 2.0D, 0.0F, 0.0F);
        data.reserveServer(
                "Edda",
                citizenId,
                "world",
                bodyOwnerId,
                "herald",
                "village",
                "The village herald.",
                firstHome);
        CitizenRegistryData.CitizenRecord original = data.findByName("edda").orElseThrow();

        data.setDirty(false);
        CitizenRegistryData.CitizenRecord withPersona = data.updatePersona(
                "EDDA", "  The village herald and keeper of records.  ").orElseThrow();

        assertEquals(citizenId, withPersona.citizenId());
        assertEquals(original.name(), withPersona.name());
        assertEquals(original.logicalOwner(), withPersona.logicalOwner());
        assertEquals(bodyOwnerId, withPersona.bodyOwnerId());
        assertEquals(original.brainController(), withPersona.brainController());
        assertEquals(original.role(), withPersona.role());
        assertEquals(original.faction(), withPersona.faction());
        assertEquals(original.home(), withPersona.home());
        assertEquals("The village herald and keeper of records.", withPersona.persona());
        assertTrue(data.isDirty());

        CitizenRegistryData.HomeAnchor movedHome = new CitizenRegistryData.HomeAnchor(
                "minecraft:the_end", -5.5D, 80.0D, 9.25D, -90.0F, 20.0F);
        data.setDirty(false);
        CitizenRegistryData.CitizenRecord withHome =
                data.updateHome("Edda", movedHome).orElseThrow();

        assertEquals(citizenId, withHome.citizenId());
        assertEquals(withPersona.logicalOwner(), withHome.logicalOwner());
        assertEquals(bodyOwnerId, withHome.bodyOwnerId());
        assertEquals(withPersona.profile(), withHome.profile());
        assertEquals(movedHome, withHome.home().orElseThrow());
        assertTrue(data.isDirty());

        data.setDirty(false);
        CitizenRegistryData.CitizenRecord withoutHome = data.updateHome(
                "edda", java.util.Optional.empty()).orElseThrow();
        assertTrue(withoutHome.home().isEmpty());
        assertEquals(withHome.persona(), withoutHome.persona());
        assertTrue(data.isDirty());

        data.setDirty(false);
        assertTrue(data.updatePersona("missing", "Nobody").isEmpty());
        assertTrue(data.updateHome("missing", movedHome).isEmpty());
        assertFalse(data.isDirty());
    }

    @Test
    void validatesPersonaAndHomeBounds() {
        String maximumPersona = "x".repeat(CitizenRegistryData.MAX_PERSONA_LENGTH);
        CitizenRegistryData.CitizenProfile maximum =
                new CitizenRegistryData.CitizenProfile("lore", "neutral", maximumPersona);
        assertEquals(CitizenRegistryData.MAX_PERSONA_LENGTH, maximum.persona().length());

        assertThrows(IllegalArgumentException.class,
                () -> new CitizenRegistryData.CitizenProfile(
                        "lore", "neutral", maximumPersona + "x"));
        assertThrows(NullPointerException.class,
                () -> new CitizenRegistryData.CitizenProfile("lore", "neutral", null));
        assertThrows(IllegalArgumentException.class,
                () -> new CitizenRegistryData.HomeAnchor(
                        "Not A Dimension", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
        assertThrows(IllegalArgumentException.class,
                () -> new CitizenRegistryData.HomeAnchor(
                        "minecraft:overworld", Double.NaN, 64.0D, 0.0D, 0.0F, 0.0F));
        assertThrows(IllegalArgumentException.class,
                () -> new CitizenRegistryData.HomeAnchor(
                        "minecraft:overworld", 0.0D, Double.POSITIVE_INFINITY, 0.0D, 0.0F, 0.0F));
        assertThrows(IllegalArgumentException.class,
                () -> new CitizenRegistryData.HomeAnchor(
                        "minecraft:overworld", 0.0D, 64.0D, 0.0D, Float.NaN, 0.0F));

        CitizenRegistryData.HomeAnchor normalized = new CitizenRegistryData.HomeAnchor(
                "  village:hall  ", 1.0D, 2.0D, 3.0D, 360.0F, -120.0F);
        assertEquals("village:hall", normalized.dimension());
    }

    @Test
    void damagedLoreFieldsDoNotDiscardPersistentIdentity() {
        UUID citizenId = UUID.randomUUID();
        UUID bodyOwnerId = UUID.randomUUID();
        CitizenRegistryData data = new CitizenRegistryData();
        data.reserveServer(
                "Keeper",
                citizenId,
                "world",
                bodyOwnerId,
                "lore",
                "neutral",
                "Keeps the old library.",
                new CitizenRegistryData.HomeAnchor(
                        "minecraft:overworld", 4.0D, 70.0D, 8.0D, 0.0F, 0.0F));
        CompoundTag saved = data.save(new CompoundTag());
        CompoundTag row = saved.getList("Citizens", CompoundTag.TAG_COMPOUND).getCompound(0);
        row.putString("Persona", "x".repeat(CitizenRegistryData.MAX_PERSONA_LENGTH + 1));
        row.getCompound("Home").putString("Dimension", "not valid");

        CitizenRegistryData loaded = CitizenRegistryData.load(saved);

        CitizenRegistryData.CitizenRecord recovered =
                loaded.findByCitizenId(citizenId).orElseThrow();
        assertEquals("Keeper", recovered.name());
        assertEquals(bodyOwnerId, recovered.bodyOwnerId());
        assertEquals("", recovered.persona());
        assertTrue(recovered.home().isEmpty());
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
