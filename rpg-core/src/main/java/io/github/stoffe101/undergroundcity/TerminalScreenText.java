package io.github.stoffe101.undergroundcity;

/** Tiny shared text helpers that must be usable on a dedicated server (no client classes). */
final class TerminalScreenText {
    private TerminalScreenText() {}

    static String capitalize(String id) {
        return id == null || id.isEmpty() ? "" : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}
