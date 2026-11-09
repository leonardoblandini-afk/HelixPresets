package it.helixpresets.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Riproduce la sequenza vista nella cattura e legge l'elenco dei preset (comando 1 sulla sessione 0x1001/0x03EF).
 * Ordine: tre sessioni aperte con il saluto, poi la sessione principale riaperta e interrogata.
 */
public final class PresetReader {
    private static final int HOST_MAIN = 0x1001, DEV_MAIN = 0x03ef;
    private static final int HOST_B = 0x1080, DEV_B = 0x03ed;
    private static final int HOST_C = 0x1002, DEV_C = 0x03f0;
    private static final long TIMEOUT = 4000;

    private PresetReader() { }

    public static List<Preset> read(Transport tp, Log log) throws IOException {
        Link link = new Link(tp, log);
        Session a = link.session(HOST_MAIN, DEV_MAIN);
        Session b = link.session(HOST_B, DEV_B);
        Session c = link.session(HOST_C, DEV_C);

        log.line("-- saluto sessione principale");
        a.open();
        a.hello(5);
        a.await(TIMEOUT, "risposta al saluto (id 5)");
        a.sendAck();
        a.closeSoft();

        log.line("-- altre due sessioni");
        b.open();
        b.hello(6);
        b.await(TIMEOUT, "risposta al saluto (id 6)");
        c.open();
        c.hello(4);
        b.sendAck();
        c.await(TIMEOUT, "risposta al saluto (id 4)");
        c.sendAck();

        log.line("-- riapertura sessione principale");
        a.open();
        a.hello(2);
        a.await(TIMEOUT, "risposta al saluto (id 2)");
        a.sendAck();

        log.line("-- richieste");
        request(a, 1000, 254, new long[0]);   // {} come nel log
        request(a, 1001, 0, null);             // elenco con la sola voce "PRESETS"
        Map<Object, Object> r = request(a, 1002, 1, new long[] { 107, 0, 101, 2 });
        List<Preset> presets = parsePresets(r);

        a.sendAck();
        a.closeFinal();
        b.closeFinal();
        c.closeFinal();
        return presets;
    }

    private static Map<Object, Object> request(Session s, int id, int cmd, long[] args) throws IOException {
        int sentId = s.sendRequest(cmd, args);
        if (sentId != id) throw new IOException("ID mismatch: expected " + id + " but sent " + sentId);
        Message m = s.await(TIMEOUT, "risposta al comando " + cmd);
        Object o = MsgPack.decode(m.data);
        if (!(o instanceof Map)) throw new IOException("risposta non valida al comando " + cmd);
        @SuppressWarnings("unchecked") Map<Object, Object> r = (Map<Object, Object>) o;
        if (!Long.valueOf(id).equals(r.get(102L))) throw new IOException("id risposta diverso da " + id);
        if (!Long.valueOf(0).equals(r.get(103L))) throw new IOException("comando " + cmd + ": stato " + r.get(103L));
        return r;
    }

    /** Record: {slot: {109: nome, ...}}; risultato in chiave 104. */
    public static List<Preset> parsePresets(Map<Object, Object> reply) throws IOException {
        Object res = reply.get(104L);
        if (!(res instanceof List)) throw new IOException("elenco preset assente");
        List<Preset> out = new ArrayList<>();
        int pos = 0;
        for (Object rec : (List<?>) res) {
            Map<?, ?> m = (Map<?, ?>) rec;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Map<?, ?> body = (Map<?, ?>) e.getValue();
                byte[] n = (byte[]) body.get(109L);
                int len = n.length;
                while (len > 0 && n[len - 1] == 0) len--;
                out.add(new Preset(pos++, ((Long) e.getKey()).intValue(), new String(n, 0, len, StandardCharsets.UTF_8)));
            }
        }
        Collections.sort(out, new Comparator<Preset>() {
            @Override public int compare(Preset x, Preset y) { return x.key - y.key; }
        });
        return out;
    }
}
