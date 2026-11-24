package studio.alans.fxwatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * A tiny read-only web server on 127.0.0.1 only, so the Garmin Connect app on this phone can fetch
 * {@code http://127.0.0.1:<port>/state.json} on behalf of the watch (the same way xDrip+'s own
 * server feeds its watch faces). It answers GET /state.json with the calm state and nothing else:
 * no other path, no other method, no writes, no way in from the network.
 *
 * state.json: state, word, colour, glucose, trend, age_min, updated (and a text line). The glucose
 * number is in it for the deliberate glance only; the watch face never draws it.
 *
 * Any app on this phone can read it, because it is plain localhost. Turn it off in the app if you
 * don't want that.
 */
final class CalmServer {

    static final int DEF_PORT = 17590;
    private static final int MAX_REQUEST_LINE = 512;
    private static final int READ_TIMEOUT_MS = 3000;

    private final Supplier<String> json;
    private ServerSocket socket;
    private Thread thread;

    CalmServer(Supplier<String> json) {
        this.json = json;
    }

    /** Binds to the loopback address only. Port 0 picks a free one (tests). */
    synchronized int start(int port) throws IOException {
        if (socket != null) return socket.getLocalPort();
        ServerSocket s = new ServerSocket(port, 8, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        socket = s;
        thread = new Thread(() -> serve(s), "FXWatcher-calm-server");
        thread.setDaemon(true);
        thread.start();
        return s.getLocalPort();
    }

    synchronized void stop() {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) { }
        socket = null;
        thread = null;
    }

    synchronized boolean running() {
        return socket != null && !socket.isClosed();
    }

    InetAddress address() {
        return socket == null ? null : socket.getInetAddress();
    }

    private void serve(ServerSocket s) {
        while (!s.isClosed()) {
            try (Socket c = s.accept()) {
                c.setSoTimeout(READ_TIMEOUT_MS);
                handle(c);
            } catch (IOException e) {
                if (s.isClosed()) return;
            } catch (RuntimeException ignored) {
                // one bad request must not stop the server
            }
        }
    }

    private void handle(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        String line = readLine(r);
        // drain the headers (we do not use them)
        for (int i = 0; i < 64; i++) {
            String h = readLine(r);
            if (h == null || h.isEmpty()) break;
        }
        byte[] out = respond(line, json);
        OutputStream o = c.getOutputStream();
        o.write(out);
        o.flush();
    }

    private static String readLine(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = r.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > MAX_REQUEST_LINE) return sb.toString();
        }
        return ch == -1 && sb.length() == 0 ? null : sb.toString();
    }

    /** The whole response for a request line. Pure, so it is unit tested without a socket. */
    static byte[] respond(String requestLine, Supplier<String> json) {
        if (requestLine == null || requestLine.length() > MAX_REQUEST_LINE) return plain(400, "Bad Request", "bad request");
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return plain(400, "Bad Request", "bad request");
        if (!"GET".equals(parts[0])) return plain(405, "Method Not Allowed", "read only");
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (!"/state.json".equals(path)) return plain(404, "Not Found", "not found");
        String body;
        try {
            body = json.get();
        } catch (RuntimeException e) {
            return plain(500, "Internal Server Error", "error");
        }
        return build(200, "OK", "application/json; charset=utf-8", body);
    }

    private static byte[] plain(int code, String reason, String body) {
        return build(code, reason, "text/plain; charset=utf-8", body);
    }

    private static byte[] build(int code, String reason, String type, String body) {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + (code == 405 ? "Allow: GET\r\n" : "")
                + "\r\n";
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[h.length + b.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(b, 0, out, h.length, b.length);
        return out;
    }

    // ---- the one instance the app runs ----

    private static CalmServer instance;

    /** Starts (or stops) the server to match the setting. Called when the notification listener connects and when settings are saved. */
    static synchronized void sync(android.content.Context c) {
        android.content.SharedPreferences p = Store.p(c);
        boolean on = p.getBoolean(Store.SERVER_ON, true);
        if (!on) {
            if (instance != null) {
                instance.stop();
                instance = null;
                Store.log(c, "Watch server stopped");
            }
            return;
        }
        int port = p.getInt(Store.SERVER_PORT, DEF_PORT);
        if (instance != null && instance.running()) return;
        final android.content.Context app = c.getApplicationContext();
        CalmServer s = new CalmServer(() -> Calm.json(app));
        try {
            s.start(port);
            instance = s;
            Store.log(c, "Watch server listening on 127.0.0.1:" + port);
        } catch (IOException e) {
            Store.log(c, "Watch server could not start on port " + port + ": " + e.getMessage());
        }
    }
}
