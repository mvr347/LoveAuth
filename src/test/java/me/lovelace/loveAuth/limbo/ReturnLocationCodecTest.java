package me.lovelace.loveAuth.limbo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReturnLocationCodecTest {
    @Test
    void roundTrip() {
        var p = new ReturnLocationCodec.Point("world_nether", -123.456789, 64.0, 1e7 + 0.25, -179.5f, 42.125f);
        String s = ReturnLocationCodec.encode(p);
        assertEquals(p, ReturnLocationCodec.decode(s));
    }

    @Test
    void rejectsMalformed() {
        assertNull(ReturnLocationCodec.decode(null));
        assertNull(ReturnLocationCodec.decode(""));
        assertNull(ReturnLocationCodec.decode("world;1;2;3;4"));
        assertNull(ReturnLocationCodec.decode(";1;2;3;4;5"));
        assertNull(ReturnLocationCodec.decode("world;a;2;3;4;5"));
        assertNull(ReturnLocationCodec.decode("world;NaN;2;3;4;5"));
        assertNull(ReturnLocationCodec.encode(new ReturnLocationCodec.Point("a;b", 0, 0, 0, 0, 0)));
    }
}
