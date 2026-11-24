package studio.alans.fxwatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class CalmServerTest {

    private static final String JSON = "{\"state\":\"steady\",\"word\":\"Steady\"}";

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    public void getStateJsonGivesTheJson() {
        String r = text(CalmServer.respond("GET /state.json HTTP/1.1", () -> JSON));
        assertTrue(r, r.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(r, r.contains("Content-Type: application/json"));
        assertTrue(r, r.contains("Content-Length: " + JSON.length()));
        assertTrue(r, r.contains("Cache-Control: no-store"));
        assertTrue(r, r.endsWith("\r\n\r\n" + JSON));
    }

    @Test
    public void aQueryStringIsIgnored() {
        assertTrue(text(CalmServer.respond("GET /state.json?x=1 HTTP/1.1", () -> JSON)).startsWith("HTTP/1.1 200"));
    }

    @Test
    public void otherPathsAre404() {
        for (String path : new String[]{"/", "/state", "/state.json/", "/../state.json", "/settings", "/log"}) {
            assertTrue(path, text(CalmServer.respond("GET " + path + " HTTP/1.1", () -> JSON)).startsWith("HTTP/1.1 404"));
        }
    }

    @Test
    public void onlyGetIsAllowed() {
        for (String m : new String[]{"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "get"}) {
            String r = text(CalmServer.respond(m + " /state.json HTTP/1.1", () -> JSON));
            assertTrue(m + ": " + r, r.startsWith("HTTP/1.1 405"));
            assertTrue(r, r.contains("Allow: GET"));
            assertFalse("no state in a refusal", r.contains("steady"));
        }
    }

    @Test
    public void garbageIsA400() {
        assertTrue(text(CalmServer.respond(null, () -> JSON)).startsWith("HTTP/1.1 400"));
        assertTrue(text(CalmServer.respond("", () -> JSON)).startsWith("HTTP/1.1 400"));
        assertTrue(text(CalmServer.respond("GET", () -> JSON)).startsWith("HTTP/1.1 400"));
        StringBuilder big = new StringBuilder("GET /");
        for (int i = 0; i < 600; i++) big.append('a');
        assertTrue(text(CalmServer.respond(big.toString(), () -> JSON)).startsWith("HTTP/1.1 400"));
    }

    @Test
    public void aFailingSupplierIsA500NotACrash() {
        assertTrue(text(CalmServer.respond("GET /state.json HTTP/1.1", () -> {
            throw new IllegalStateException("boom");
        })).startsWith("HTTP/1.1 500"));
    }

    private static String fetch(int port, String request) throws Exception {
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port)) {
            s.setSoTimeout(4000);
            OutputStream o = s.getOutputStream();
            o.write(request.getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    @Test
    public void realSocketIsLoopbackOnlyAndServesTheJson() throws Exception {
        CalmServer server = new CalmServer(() -> JSON);
        try {
            int port = server.start(0);
            assertTrue("bound to 127.0.0.1 only, not 0.0.0.0", server.address().isLoopbackAddress());
            assertFalse(server.address().isAnyLocalAddress());
            String ok = fetch(port, "GET /state.json HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            assertTrue(ok, ok.startsWith("HTTP/1.1 200 OK"));
            assertTrue(ok, ok.endsWith(JSON));
            assertTrue(fetch(port, "POST /state.json HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 405"));
            assertTrue(fetch(port, "GET /nothing HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"));
            // still serving after the odd requests
            assertTrue(fetch(port, "GET /state.json HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"));
        } finally {
            server.stop();
        }
        assertFalse(server.running());
    }

    @Test
    public void startTwiceKeepsOneSocket() throws Exception {
        CalmServer server = new CalmServer(() -> JSON);
        try {
            int a = server.start(0);
            int b = server.start(0);
            assertEquals(a, b);
        } finally {
            server.stop();
        }
    }

    @Test
    public void historyIsServedOnlyWhenGivenAndStaysReadOnly() {
        assertTrue(text(CalmServer.respond("GET /history.json HTTP/1.1", () -> JSON)).startsWith("HTTP/1.1 404"));
        String r = text(CalmServer.respond("GET /history.json HTTP/1.1", () -> JSON, () -> "{\"h\":[]}"));
        assertTrue(r.startsWith("HTTP/1.1 200"));
        assertTrue(r.endsWith("{\"h\":[]}"));
        assertTrue(text(CalmServer.respond("POST /history.json HTTP/1.1", () -> JSON, () -> "{}")).startsWith("HTTP/1.1 405"));
        assertTrue(text(CalmServer.respond("GET /history HTTP/1.1", () -> JSON, () -> "{}")).startsWith("HTTP/1.1 404"));
    }
}
