package io.github.mizar107.zapegcitizens.brain;

import io.github.mizar107.zapegcitizens.chat.TurkishFold;

import java.util.regex.Pattern;

/**
 * Keeps a durable job on the original player instruction across several world
 * actions. Asking the owner to say “now craft” or “now continue” is not a real
 * blocker when that next step was already implied.
 *
 * <p>The re-issue net is bilingual (the live server speaks Turkish): it also
 * matches "devam de", "... edeyim mi", and "seni bekliyorum" phrasings.
 * Matching runs over {@link TurkishFold#fold} output so {@code İ}/{@code I}/
 * {@code ı} all compare as a plain {@code i}, and the patterns are written in
 * that folded form ({@code koyayim}, {@code hazir}).
 */
public final class InstructionPolicy {

    static final String CONTINUE_ORIGINAL_INSTRUCTION =
            "The original instruction still stands for this whole job. Do not wait "
                    + "for another player command. Call the next remaining world tool "
                    + "toward completing it now.";

    private static final Pattern REISSUE = Pattern.compile(
            "\\b("
                    + "now (continue|craft|deposit|put|store)|"
                    + "say continue|"
                    + "tell me to|"
                    + "waiting for (you|the (owner|player))|"
                    + "should i (continue|proceed|put|deposit|craft|store|keep going)|"
                    + "do you want me to (continue|put|deposit|craft)|"
                    + "ready for (the )?next|"
                    + "devam (de|der misin|dersen|komutu)|"
                    + "devam etmemi (söyle|soyle|iste|ister)\\w*|"
                    + "(devam|koymami|craftlamami|depolamami) ister misin\\w*|"
                    + "(edeyim|koyayim|yapayim|craftlayayim|depolayayim|başlayayim|"
                    + "baslayayim) mi\\b|"
                    + "(seni|onayini|komutunu?) bekliyorum|"
                    + "sonraki (adim|görev|gorev)[^.]{0,12}hazir\\w*"
                    + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    | Pattern.UNICODE_CHARACTER_CLASS);

    private InstructionPolicy() {}

    public static boolean isSequenceReissueRequest(String goal, String question) {
        return REISSUE.matcher(TurkishFold.fold(question)).find();
    }

    public static String continueOriginalInstructionAnswer() {
        return CONTINUE_ORIGINAL_INSTRUCTION;
    }
}
