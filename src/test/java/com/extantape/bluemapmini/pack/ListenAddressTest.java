package com.extantape.bluemapmini.pack;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/** pack.bind: where the pack server listens, and that a typo never means "everywhere". */
class ListenAddressTest {
    @Test void emptyListensOnEveryAddressAsBefore() {
        InetSocketAddress a = PackService.listenAddress(8164, "");
        assertTrue(a.getAddress().isAnyLocalAddress());
        assertEquals(8164, a.getPort());
    }

    @Test void loopbackStaysOnThisMachine() {
        InetSocketAddress a = PackService.listenAddress(8164, "127.0.0.1");
        assertTrue(a.getAddress().isLoopbackAddress());
        assertTrue(PackService.listenAddress(8164, "::1").getAddress().isLoopbackAddress());
    }

    @Test void aHostNameOrTypoIsRefusedNotWidened() {
        assertNull(PackService.listenAddress(8164, "localhost"), "names aren't looked up");
        assertNull(PackService.listenAddress(8164, "127.0.0.l"));
        assertNull(PackService.listenAddress(8164, "0.0.0.0/0"));
    }

    @Test void aServerReallyListensOnlyWhereTold() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(PackService.listenAddress(0, "127.0.0.1"), 1);
        try {
            server.start();
            assertTrue(server.getAddress().getAddress().isLoopbackAddress());
        } finally {
            server.stop(0);
        }
    }
}
