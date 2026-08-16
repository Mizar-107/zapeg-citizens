package io.github.mizar107.zapegcitizens.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses explicit citizen addresses without turning ordinary chat into agent input. */
public record CitizenChatAddress(String citizenName, String prompt) {

    private static final Pattern ADDRESS =
            Pattern.compile("^@([A-Za-z0-9_]{3,16})\\s+(.{1,256})$");

    public static Optional<CitizenChatAddress> parse(String rawText) {
        if (rawText == null) {
            return Optional.empty();
        }
        Matcher matcher = ADDRESS.matcher(rawText.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String prompt = matcher.group(2).strip();
        if (prompt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CitizenChatAddress(matcher.group(1), prompt));
    }
}
