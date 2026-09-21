package net.clanimg.crossClipboard;

import java.util.Locale;

/** Human-readable sizes and durations for player messages. */
public final class Format {

    private Format() {
    }

    /** {@code 1536} becomes {@code 1.5 KB}. */
    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** {@code 7500} seconds becomes {@code 2h 5m}. */
    public static String duration(long seconds) {
        if (seconds < 60) {
            return Math.max(0, seconds) + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + "h " + (minutes % 60) + "m" : (hours / 24) + "d";
    }
}
