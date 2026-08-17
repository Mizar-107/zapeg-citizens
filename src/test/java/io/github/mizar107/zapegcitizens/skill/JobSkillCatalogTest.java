package io.github.mizar107.zapegcitizens.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JobSkillCatalogTest {

    @Test
    void exposesFourStableTrustedWorkflows() {
        assertEquals(
                List.of("storage", "building", "mining", "combat"),
                JobSkillCatalog.names());
        assertEquals(4, JobSkillCatalog.verifyResources());

        assertTrue(JobSkillCatalog.require("storage").contains("exact item stacks"));
        assertTrue(JobSkillCatalog.require("building").contains("Plan before mutation"));
        assertTrue(JobSkillCatalog.require("mining").contains("diamond_ore"));
        assertTrue(JobSkillCatalog.require("combat").contains("confirmed target deaths"));
    }

    @Test
    void rejectsUnknownNamesAndPathsInsteadOfReadingArbitraryResources() {
        assertFalse(JobSkillCatalog.find("building_design").isPresent());
        assertFalse(JobSkillCatalog.find("../META-INF/mods.toml").isPresent());
        assertFalse(JobSkillCatalog.find(null).isPresent());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> JobSkillCatalog.require("../storage"));
        assertTrue(error.getMessage().contains("choose one of"));
    }
}
