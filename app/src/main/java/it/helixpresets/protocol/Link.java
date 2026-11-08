package it.helixpresets.protocol;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Un solo canale USB condiviso da più sessioni; le trame in arrivo vanno alla sessione giusta (campo mittente). */
public final class Link {
    public interface Condition { boolean ok(); }

    private final Transport tp;
    private final Log log;
    private final Map<Integer, Session> byDevice = new HashMap<>();

    public Link(Transport tp, Log log) { this.tp = tp; this.log = log; }

    public Session session(int hostId, int deviceId) {
        Session s = new Session(this, hostId, deviceId);
        byDevice.put(deviceId, s);
        return s;
    }

    void write(Frame f) throws IOException {
        byte[] b = f.toBytes();
        log.line("OUT " + Frame.hex(b));
        tp.write(b);
    }

    /** Legge una trama (max timeoutMs) e la consegna alla sessione. */
    void pump(int timeoutMs) throws IOException {
        byte[] raw = tp.read(timeoutMs);
        if (raw == null) return;
        log.line("IN  " + Frame.hex(raw));
        try {
            Frame f = Frame.parse(raw, raw.length);
            Session s = byDevice.get(f.src);
            if (s != null) s.onFrame(f);
        } catch (IllegalArgumentException e) {
            log.line("trama ignorata: " + e.getMessage());
        }
    }

    void waitFor(Condition c, long timeoutMs, String what) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!c.ok()) {
            if (System.currentTimeMillis() > end) throw new IOException("timeout: " + what);
            pump(100);
        }
    }
}
