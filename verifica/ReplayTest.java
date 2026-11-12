import it.helixpresets.protocol.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Fa rispondere la cattura al posto della HX Stomp e controlla elenco preset e trame inviate. */
public class ReplayTest {
    static boolean isHost(Frame f) { return (f.src >> 8) == 0x10; }

    static class FakeDevice implements Transport {
        final Map<Integer, ArrayDeque<List<byte[]>>> batches = new HashMap<>();
        final Map<Integer, List<List<byte[]>>> expectedHost = new HashMap<>();
        final ArrayDeque<byte[]> in = new ArrayDeque<>();
        final List<Frame> sent = new ArrayList<>();
        final Map<Integer, List<Frame>> capturedHost = new HashMap<>();

        FakeDevice(List<byte[]> capture) {
            Map<Integer, List<byte[]>> cur = new HashMap<>();
            int last = -1; // sessione dell'ultima trama host non-ack
            for (byte[] raw : capture) {
                Frame f = Frame.parse(raw, raw.length);
                if (isHost(f)) {
                    if (f.type == Frame.TYPE_ACK || f.type == Frame.TYPE_CLOSE) continue;
                    List<byte[]> b = new ArrayList<>();
                    cur.put(f.dst, b);
                    last = f.dst;
                    batches.computeIfAbsent(f.dst, k -> new ArrayDeque<>()).add(b);
                    capturedHost.computeIfAbsent(f.dst, k -> new ArrayList<>()).add(f);
                } else if (f.src == 0x03f0 && last != 0x03f0 && cur.get(last) != null) {
                    cur.get(last).add(raw);          // eventi spontanei: arrivano dopo l'ultima richiesta, di qualunque sessione
                } else if (cur.get(f.src) != null) {
                    cur.get(f.src).add(raw);
                }
            }
        }

        @Override public void write(byte[] frame) {
            Frame f = Frame.parse(frame, frame.length);
            sent.add(f);
            if (f.type == Frame.TYPE_ACK || f.type == Frame.TYPE_CLOSE) return;
            ArrayDeque<List<byte[]>> q = batches.get(f.dst);
            if (q != null && !q.isEmpty()) in.addAll(q.poll());
        }

        @Override public byte[] read(int timeoutMs) { return in.poll(); }
    }

    public static void main(String[] a) throws Exception {
        List<byte[]> capture = new ArrayList<>();
        for (String blk : new String(Files.readAllBytes(Paths.get(a[0]))).trim().split("\n\n")) {
            String[] t = blk.trim().split("\\s+");
            byte[] b = new byte[t.length];
            for (int i = 0; i < t.length; i++) b[i] = (byte) Integer.parseInt(t[i], 16);
            capture.add(b);
        }
        FakeDevice dev = new FakeDevice(capture);
        List<String> logLines = new ArrayList<>();
        HelixConnection conn = new HelixConnection(dev, logLines::add);
        List<Preset> presets = conn.connect();
        boolean selezione = a.length > 2;
        String snapOk = "-";
        if (selezione) {
            HelixConnection.PresetInfo info = conn.select(Integer.parseInt(a[2]));
            String got3 = info.name + " | " + String.join(", ", info.snapshots) + " | attivo " + info.snapshot;
            snapOk = got3.equals(a[3]) ? "ok" : "DIVERSO";
            System.out.println("preset: " + got3 + "  (atteso: " + a[3] + ") " + snapOk);
        }
        conn.close();
        if (selezione) for (String l : logLines) if (l.startsWith("evento") || l.startsWith("-- cambio")) System.out.println(l);

        // 1) elenco preset
        List<String> got = new ArrayList<>();
        for (Preset p : presets) got.add(p.toString());
        List<String> want = Files.readAllLines(Paths.get(a[1]));
        System.out.println("preset letti: " + got.size() + ", attesi: " + want.size() + ", uguali: " + got.equals(want));

        // 2) trame inviate, per sessione, contro la cattura (solo trame non-ack)
        int tot = 0, same = 0, sameExact = 0;
        Map<Integer, List<Frame>> mine = new TreeMap<>();
        for (Frame f : dev.sent) {
            if (f.type == Frame.TYPE_ACK || f.type == Frame.TYPE_CLOSE) continue;
            mine.computeIfAbsent(f.dst, k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<Integer, List<Frame>> e : mine.entrySet()) {
            List<Frame> cap = dev.capturedHost.get(e.getKey());
            for (int i = 0; i < e.getValue().size(); i++) {
                Frame m = e.getValue().get(i), c = cap.get(i);
                tot++;
                boolean eq = m.cls == c.cls && m.src == c.src && m.dst == c.dst
                        && Arrays.equals(m.payload, c.payload)
                        && (m.type == c.type || (m.isData() && c.isData()));
                if (eq) same++; else System.out.printf("DIVERSA dev=%04x #%d%n  mia:    %s%n  cattura: %s%n", e.getKey(), i, Frame.hex(m.toBytes()), Frame.hex(c.toBytes()));
                if (m.seq == c.seq && m.type == c.type && m.ack == c.ack && eq) sameExact++;
            }
        }
        System.out.println("trame non-ack inviate: " + tot + ", uguali alla cattura (payload e indirizzi): " + same
                + ", identiche anche in seq/tipo/ack: " + sameExact);
        int acks = 0; for (Frame f : dev.sent) if (f.type == Frame.TYPE_ACK) acks++;
        System.out.println("ack puri inviati: " + acks + ", trame totali inviate: " + dev.sent.size());
        System.exit(got.equals(want) && tot == same && !snapOk.equals("DIVERSO") ? 0 : 1);
    }
}
