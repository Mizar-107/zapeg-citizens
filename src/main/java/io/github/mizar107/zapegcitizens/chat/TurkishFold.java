package io.github.mizar107.zapegcitizens.chat;

import java.util.Locale;

/**
 * Locale-safe lowercase folding for mixed Turkish/English keyword matching.
 *
 * <p>{@code toLowerCase(Locale.ROOT)} alone is not enough on a Turkish server:
 * {@code "İPTAL"} lowercases to {@code "i̇ptal"} (an {@code i} plus a
 * combining dot, U+0307) which never equals {@code "iptal"}, and sloppy ASCII
 * typing writes dotless-{@code ı} words with a plain {@code i}. This fold maps
 * every capital/dotless {@code i} variant ({@code İ I ı i̇}) to a plain
 * {@code i} before ordinary lowercasing, so keyword patterns written with a
 * plain {@code i} match all of {@code iptal}, {@code İptal}, {@code IPTAL},
 * and {@code ıptal}. Patterns must therefore also be written in folded form
 * ({@code sandik} rather than {@code sandık}); the other Turkish letters
 * ({@code ü ö ş ç ğ}) lowercase safely and stay themselves.
 */
public final class TurkishFold {

    private TurkishFold() {}

    /** Fold mixed Turkish/English text for case-insensitive keyword matching. */
    public static String fold(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replace('İ', 'i')
                .replace('I', 'i')
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i')
                .replace("i\u0307", "i");
    }
}
