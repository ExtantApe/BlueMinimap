package com.extantape.bluemapmini.pack;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicUrlCheckTest {
    private static final byte[] PACK = "a pack".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "an older pack".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpServer server;
    private String base;
    private String sha1;

    @BeforeEach
    void start() throws Exception {
        sha1 = PublicUrlCheck.sha1(new ByteArrayInputStream(PACK));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                byte[] body = path.equals("/" + sha1 + ".zip") ? PACK : path.equals("/old.zip") ? OTHER : null;
                if (path.equals("/moved")) {
                    exchange.getResponseHeaders().set("Location", "/" + sha1 + ".zip");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fillsTheHashIntoEveryPlaceholder() {
        assertEquals("https://h/t/abc.zip?v=abc", PublicUrlCheck.fill("https://h/t/{sha1}.zip?v={sha1}", "abc"));
        assertEquals("https://h/pack.zip", PublicUrlCheck.fill("https://h/pack.zip", "abc"));
    }

    @Test
    void matchesWhenTheHostedFileIsTheBuiltPack() {
        PublicUrlCheck.Result result = PublicUrlCheck.check(PublicUrlCheck.fill(base + "{sha1}.zip", sha1), sha1, TIMEOUT);
        assertTrue(result.matches(), result.message());
    }

    @Test
    void followsRedirects() {
        assertTrue(PublicUrlCheck.check(base + "moved", sha1, TIMEOUT).matches());
    }

    @Test
    void warnsWithBothHashesWhenTheHostedFileIsStale() {
        PublicUrlCheck.Result result = PublicUrlCheck.check(base + "old.zip", sha1, TIMEOUT);
        assertFalse(result.matches());
        assertTrue(result.message().contains(sha1), result.message());
        assertTrue(result.message().contains("Hosted SHA-1 "), result.message());
    }

    @Test
    void warnsOnHttpErrors() {
        PublicUrlCheck.Result result = PublicUrlCheck.check(base + "missing.zip", sha1, TIMEOUT);
        assertFalse(result.matches());
        assertTrue(result.message().contains("HTTP 404"), result.message());
    }

    @Test
    void warnsWhenUnreachableAndSaysHowToTurnTheCheckOff() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PublicUrlCheck.Result result = PublicUrlCheck.check("http://127.0.0.1:" + port + "/x.zip", sha1, TIMEOUT);
        assertFalse(result.matches());
        assertTrue(result.message().contains("verify-public-url"), result.message());
    }

    @Test
    void warnsOnAnInvalidAddress() {
        assertFalse(PublicUrlCheck.check("not a url", sha1, TIMEOUT).matches());
    }

    @Test
    void stopsReadingPastTheClientLimit() throws Exception {
        InputStream endless = new InputStream() {
            long left = PublicUrlCheck.MAX_BYTES + 1;

            @Override
            public int read() {
                return left-- > 0 ? 0 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) return -1;
                int n = (int) Math.min(len, left);
                left -= n;
                return n;
            }
        };
        assertNull(PublicUrlCheck.sha1(endless));
    }
}
