package com.extantape.bluemapmini;

import com.extantape.bluemapmini.share.ShareService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The parts of sharing that don't need a server; the live tests cover the rest. */
class ShareTest {
    @Test
    void aWorldsNetherAndEndShareItsGroup() {
        assertEquals("world", ShareService.worldGroup("world"));
        assertEquals("world", ShareService.worldGroup("world_nether"));
        assertEquals("world", ShareService.worldGroup("world_the_end"));
        assertEquals("survival", ShareService.worldGroup("survival_nether"));
        assertEquals("nether_world", ShareService.worldGroup("nether_world"), "Only a suffix counts");
        assertEquals("otherworld", ShareService.worldGroup("otherworld"));
    }
}
