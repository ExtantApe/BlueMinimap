package com.extantape.bluemapmini.pack;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads a hosted pack once and compares its SHA-1 with the pack the plugin built. The client checks
 * the same hash, so a mismatch here means players would fail the download.
 */
public final class PublicUrlCheck {
    /** The client refuses packs over 250 MiB, so there's no point reading further. */
    static final long MAX_BYTES = 250L * 1024 * 1024;
    public static final String PLACEHOLDER = "{sha1}";

    public record Result(boolean matches, String message) {}

    private PublicUrlCheck() {}

    /** The address players are sent: the configured one, with {sha1} replaced by the built pack's hash. */
    public static String fill(String template, String sha1Hex) {
        return template.replace(PLACEHOLDER, sha1Hex);
    }

    public static Result check(String url, String expectedSha1, Duration timeout) {
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(timeout).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return new Result(false, "pack.public-url answered HTTP " + response.statusCode() + " for " + url
                            + ". Players will fail the download" + kickNote() + ".");
                }
                String actual = sha1(body);
                if (actual == null) {
                    return new Result(false, "The file at pack.public-url is over 250 MiB, which the client refuses: " + url);
                }
                if (actual.equals(expectedSha1)) {
                    return new Result(true, "pack.public-url serves the current pack (SHA-1 " + expectedSha1 + ").");
                }
                return new Result(false, "The file at pack.public-url doesn't match the pack this plugin built. Hosted SHA-1 "
                        + actual + ", built " + expectedSha1 + ". Players will fail the download" + kickNote()
                        + ". Upload plugins/BlueMapMini/pack/bluemapmini-pack.zip unchanged to " + url + ".");
            }
        } catch (IllegalArgumentException e) {
            return new Result(false, "pack.public-url isn't a valid address: " + url);
        } catch (IOException e) {
            return new Result(false, "Could not download pack.public-url (" + url + "): " + describe(e)
                    + ". If the server can't reach that address but players can, set pack.verify-public-url to false.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "The pack.public-url check was interrupted.");
        }
    }

    private static String kickNote() {
        return ", and are kicked while pack.required is true";
    }

    private static String describe(IOException e) {
        if (e instanceof java.net.http.HttpTimeoutException) return "it timed out";
        if (e instanceof java.net.ConnectException) return "the connection failed";
        if (e instanceof java.net.UnknownHostException) return "the host name didn't resolve";
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Returns the hex SHA-1 of the stream, or null if it's longer than {@link #MAX_BYTES}. */
    static String sha1(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int read; (read = in.read(buffer)) >= 0; ) {
            total += read;
            if (total > MAX_BYTES) return null;
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
