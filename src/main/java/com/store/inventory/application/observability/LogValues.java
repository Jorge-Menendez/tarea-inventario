package com.store.inventory.application.observability;

/** Log formatting must never normalize or change business identifiers. */
public final class LogValues {
    private static final int MAX_LENGTH = 256;

    private LogValues() { }

    public static String safe(String value) {
        if (value == null) {
            return "<null>";
        }
        StringBuilder result = new StringBuilder();
        int limit = Math.min(value.length(), MAX_LENGTH);
        for (int i = 0; i < limit; i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
                result.append('?');
            } else {
                result.append(character);
            }
        }
        if (value.length() > MAX_LENGTH) {
            result.append("[truncated]");
        }
        return result.toString();
    }
}
