package io.github.mizar107.zapegcitizens.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CitizenForgeEventsTest {

    @Test
    void stopMeansEverythingAndCancelMeansOnlyTheActiveJob() {
        assertTrue(CitizenForgeEvents.isStopEverything("stop"));
        assertTrue(CitizenForgeEvents.isStopEverything("dur"));
        assertTrue(CitizenForgeEvents.isStopEverything("DUR"));
        assertTrue(CitizenForgeEvents.isStopEverything("  Stop  "));
        assertFalse(CitizenForgeEvents.isStopEverything("cancel"));
        assertFalse(CitizenForgeEvents.isStopEverything("iptal"));
        assertFalse(CitizenForgeEvents.isStopEverything("stop everything now"));

        assertTrue(CitizenForgeEvents.isCancelActive("cancel"));
        assertTrue(CitizenForgeEvents.isCancelActive("iptal"));
        // Turkish capital dotted İ folds to a plain i.
        assertTrue(CitizenForgeEvents.isCancelActive("İptal"));
        assertTrue(CitizenForgeEvents.isCancelActive("İPTAL"));
        assertFalse(CitizenForgeEvents.isCancelActive("stop"));
        assertFalse(CitizenForgeEvents.isCancelActive("dur"));
    }

    @Test
    void statusResumeAndAnswerKeepTheirBilingualForms() {
        assertTrue(CitizenForgeEvents.isStatus("durum"));
        assertTrue(CitizenForgeEvents.isStatus("STATUS"));
        assertFalse(CitizenForgeEvents.isStatus("done"));

        assertTrue(CitizenForgeEvents.isResume("devam"));
        assertTrue(CitizenForgeEvents.isResume("DEVAM"));
        assertTrue(CitizenForgeEvents.isResume("resume"));
        assertFalse(CitizenForgeEvents.isResume("devam et lütfen"));

        assertEquals("64 tane", CitizenForgeEvents.answerText("answer 64 tane"));
        assertEquals("64 tane", CitizenForgeEvents.answerText("cevap 64 tane"));
        assertEquals("meşe kullan", CitizenForgeEvents.answerText("meşe kullan"));
    }
}
