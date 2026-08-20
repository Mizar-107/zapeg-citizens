package io.github.mizar107.zapegcitizens.brain;

import java.util.regex.Pattern;

/**
 * Keeps a durable job on the original player instruction across several world
 * actions. Asking the owner to say “now craft” or “now continue” is not a real
 * blocker when that next step was already implied.
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
                    + "ready for (the )?next"
                    + ")\\b",
            Pattern.CASE_INSENSITIVE);

    private InstructionPolicy() {}

    public static boolean isSequenceReissueRequest(String goal, String question) {
        String questionText = question == null ? "" : question;
        return REISSUE.matcher(questionText).find();
    }

    public static String continueOriginalInstructionAnswer() {
        return CONTINUE_ORIGINAL_INSTRUCTION;
    }
}
