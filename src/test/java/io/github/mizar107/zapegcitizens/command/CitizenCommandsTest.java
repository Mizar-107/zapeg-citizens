package io.github.mizar107.zapegcitizens.command;

import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.HomeAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CitizenCommandsTest {

    @Test
    void citizenNamesUseMinecraftPlayerNameShape() {
        assertTrue(CitizenCommands.validCitizenName("Lore_01"));
        assertTrue(CitizenCommands.validCitizenName("abc"));
        assertFalse(CitizenCommands.validCitizenName("ab"));
        assertFalse(CitizenCommands.validCitizenName("has-dash"));
        assertFalse(CitizenCommands.validCitizenName("abcdefghijklmnopq"));
    }

    @Test
    void roleAndFactionIdentifiersAreBounded() {
        assertTrue(CitizenCommands.validProfileIdentifier("lore"));
        assertTrue(CitizenCommands.validProfileIdentifier("zapeg:town_guard"));
        assertTrue(CitizenCommands.validProfileIdentifier("enemy.boss-1"));
        assertFalse(CitizenCommands.validProfileIdentifier(""));
        assertFalse(CitizenCommands.validProfileIdentifier("two words"));
        assertFalse(CitizenCommands.validProfileIdentifier("x".repeat(33)));
    }

    @Test
    void homeFormattingIsLocaleIndependentAndReadable() {
        HomeAnchor home = new HomeAnchor(
                "minecraft:overworld", 12.25D, 64.0D, -8.75D, 90.0F, 0.0F);

        assertEquals(
                "minecraft:overworld@(12.3, 64.0, -8.8)",
                CitizenCommands.formatHome(home));
    }
}
