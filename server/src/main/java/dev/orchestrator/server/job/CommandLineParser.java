package dev.orchestrator.server.job;

import dev.orchestrator.config.FlowConfig;
import dev.orchestrator.domain.ExecutionRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses one command line from the palette: {@code <target...> [--preset X] [--focus X]* [--language X]}.
 * Supports double/single quotes; a leading {@code run} is ignored. Without {@code --preset}
 * the default preset runs.
 */
public final class CommandLineParser {
    private CommandLineParser() {
    }

    public static ExecutionRequest parse(String line) {
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Empty command");
        }
        int start = "run".equalsIgnoreCase(tokens.get(0)) ? 1 : 0;
        String preset = FlowConfig.DEFAULT_PRESET;
        List<String> target = new ArrayList<>();
        List<String> focus = new ArrayList<>();
        String language = "ko";
        for (int i = start; i < tokens.size(); i++) {
            String token = tokens.get(i);
            switch (token) {
                case "--preset", "-p" -> preset = requireValue(tokens, ++i, token);
                case "--focus" -> focus.add(requireValue(tokens, ++i, token));
                case "--language" -> language = requireValue(tokens, ++i, token);
                default -> {
                    if (token.startsWith("--preset=")) {
                        preset = token.substring("--preset=".length());
                    } else if (token.startsWith("--focus=")) {
                        focus.add(token.substring("--focus=".length()));
                    } else if (token.startsWith("--language=")) {
                        language = token.substring("--language=".length());
                    } else if (token.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + token);
                    } else {
                        target.add(token);
                    }
                }
            }
        }
        if (target.isEmpty()) {
            throw new IllegalArgumentException("Target is required");
        }
        return new ExecutionRequest(preset, String.join(" ", target), focus, language);
    }

    private static String requireValue(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return tokens.get(index);
    }

    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (char c : line.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
