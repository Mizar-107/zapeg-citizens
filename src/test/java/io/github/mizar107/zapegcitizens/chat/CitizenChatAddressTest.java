package io.github.mizar107.zapegcitizens.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CitizenChatAddressTest {

    @Test
    void parsesExplicitAddress() {
        CitizenChatAddress address = CitizenChatAddress
                .parse("@Atlas go collect iron")
                .orElseThrow();

        assertEquals("Atlas", address.citizenName());
        assertEquals("go collect iron", address.prompt());
    }

    @Test
    void leavesOrdinaryChatAlone() {
        assertTrue(CitizenChatAddress.parse("Atlas should collect iron").isEmpty());
        assertTrue(CitizenChatAddress.parse("hello everyone").isEmpty());
    }

    @Test
    void rejectsInvalidCitizenName() {
        assertTrue(CitizenChatAddress.parse("@a do something").isEmpty());
        assertTrue(CitizenChatAddress.parse("@not-a-name do something").isEmpty());
    }
}
