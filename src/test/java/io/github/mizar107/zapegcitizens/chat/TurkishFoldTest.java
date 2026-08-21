package io.github.mizar107.zapegcitizens.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TurkishFoldTest {

    @Test
    void foldsEveryTurkishIVariantToPlainI() {
        assertEquals("balta", TurkishFold.fold("BALTA"));
        assertEquals("iptal", TurkishFold.fold("İptal"));
        assertEquals("iptal", TurkishFold.fold("İPTAL"));
        assertEquals("iptal", TurkishFold.fold("ıptal"));
        assertEquals("iptal", TurkishFold.fold("i\u0307ptal"));
        assertEquals("sandik", TurkishFold.fold("SANDIK"));
        assertEquals("sandik", TurkishFold.fold("sandık"));
    }

    @Test
    void preservesOtherTurkishLettersAndEnglish() {
        assertEquals("kürek", TurkishFold.fold("KÜREK"));
        assertEquals("kömür", TurkishFold.fold("Kömür"));
        assertEquals("ağaç", TurkishFold.fold("AĞAÇ"));
        assertEquals("chop 8 logs", TurkishFold.fold("Chop 8 LOGS"));
        assertEquals("", TurkishFold.fold(null));
        assertEquals("", TurkishFold.fold(""));
    }
}
