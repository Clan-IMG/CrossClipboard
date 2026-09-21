package net.clanimg.crossClipboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatTest {

    @Test
    void formatsByteCounts() {
        assertEquals("0 B", Format.bytes(0));
        assertEquals("1023 B", Format.bytes(1023));
        assertEquals("1.0 KB", Format.bytes(1024));
        assertEquals("1.5 KB", Format.bytes(1536));
        assertEquals("50.0 MB", Format.bytes(50L * 1024 * 1024));
        assertEquals("2.0 GB", Format.bytes(2L * 1024 * 1024 * 1024));
    }

    @Test
    void formatsDurations() {
        assertEquals("0s", Format.duration(-5));
        assertEquals("45s", Format.duration(45));
        assertEquals("5m", Format.duration(300));
        assertEquals("2h 5m", Format.duration(7500));
        assertEquals("3d", Format.duration(3 * 24 * 3600 + 100));
    }
}
