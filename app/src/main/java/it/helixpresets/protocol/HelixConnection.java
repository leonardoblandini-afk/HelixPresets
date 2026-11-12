package it.helixpresets.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Riproduce la sequenza di HX Edit vista nelle catture. Sessioni:
 *  A = 0x1001/0x03EF (elenchi), B = 0x1080/0x03ED (preset corrente e comandi), C = 0x1002/0x03F0 (eventi dal dispositivo).
 * Dopo connect() le sessioni restano aperte: select() cambia preset e legge gli snapshot, selectSnapshot() cambia snapshot, poll() smaltisce gli eventi, close() chiude.
 */
public final class HelixConnection {
    private static final long TIMEOUT = 4000;

    private final Link link;
    private final Log log;
    private final Session a, b, c;

    public HelixConnection(Transport tp, Log log) {
        this.log = log;
        link = new Link(tp, log);
        a = link.session(0x1001, 0x03ef);
        b = link.session(0x1080, 0x03ed);
        c = link.session(0x1002, 0x03f0);
        c.setAutoAck(true);
    }

    /** Apre le sessioni e legge l'elenco dei preset, nell'ordine inviato dalla HX Stomp. */
    public List<Preset> connect() throws IOException {
        log.line("-- saluto sessione A");
        a.open(); a.hello(5); a.await(TIMEOUT, "saluto A (id 5)"); a.sendAck(); a.closeSoft();

        log.line("-- sessioni B e C");
        b.open(); b.hello(6); b.await(TIMEOUT, "saluto B (id 6)");
        c.open(); c.hello(4); b.sendAck();
        c.await(TIMEOUT, "saluto C (id 4)"); c.sendAck();

        log.line("-- riapertura A");
        a.open(); a.hello(2); a.await(TIMEOUT, "saluto A (id 2)"); a.sendAck();

        log.line("-- letture iniziali di HX Edit sulla sessione B");
        ask(b, 76);
        ask(b, 24, 118, 128);
        ask(b, 23);
        ask(b, 22);                      // preset corrente completo (non usato qui)
        ask(b, 24, 118, 14);
        ask(b, 24, 118, 73);
        ask(b, 24, 118, 136);

        log.line("-- richieste sulla sessione A");
        ask(a, 254);
        ask(b, 24, 118, 27);
        ask(b, 99);
        ask(a, 0);
        Map<Object, Object> r = ask(a, 1, 107, 0, 101, 2);   // elenco dei preset
        List<Preset> presets = parsePresets(r);
        a.sendAck();
        return presets;
    }

    /** Risultato della selezione: nome del preset e nomi dei suoi 3 snapshot. */
    public static final class PresetInfo {
        public final String name;
        public final String[] snapshots;
        /** Snapshot attivo (1..3), 0 se non noto. */
        public final int snapshot;

        PresetInfo(String name, String[] snapshots, int snapshot) {
            this.name = name; this.snapshots = snapshots; this.snapshot = snapshot;
        }
    }

    /**
     * Preseleziona il preset alla posizione indicata (comando 20 sulla sessione B), poi legge come HX Edit
     * il nome (comando 23) e il preset completo (comando 22) per ricavare i nomi degli snapshot.
     */
    public PresetInfo select(int position) throws IOException {
        log.line("-- cambio preset, posizione " + position);
        ask(b, 20, 107, 0, 108, position);   // la risposta ha stato 1; l'esito (stato 0) arriva come evento sulla sessione C
        b.sendAck();
        poll(800);
        Map<Object, Object> cur = ask(b, 23);
        Map<Object, Object> full = ask(b, 22);
        b.sendAck();
        Map<?, ?> info = (Map<?, ?>) cur.get(104L);
        return new PresetInfo(text(info.get(109L)), snapshotNames((byte[]) full.get(104L)), activeSnapshot(info));
    }

    /**
     * Cambio snapshot (1..3): comando 88 con {92: n}, n da 0 a 2, sulla sessione B. La risposta ha stato 0 e i
     * cambiamenti arrivano come eventi sulla sessione C. Poi, come HX Edit, si rilegge il preset corrente (comando 23),
     * dove la chiave 92 è lo snapshot attivo. Restituisce lo snapshot attivo dopo il cambio.
     */
    public int selectSnapshot(int snapshot) throws IOException {
        if (snapshot < 1 || snapshot > 3) throw new IOException("snapshot da 1 a 3");
        log.line("-- cambio snapshot " + snapshot);
        ask(b, 88, 92, snapshot - 1);
        b.sendAck();
        poll(500);
        Map<Object, Object> cur = ask(b, 23);
        int now = activeSnapshot((Map<?, ?>) cur.get(104L));
        if (now != snapshot) throw new IOException("la HX Stomp è sullo snapshot " + now + " invece di " + snapshot);
        return now;
    }

    /** Nella risposta al comando 23 la chiave 92 è lo snapshot attivo (da 0); qui 1..3, oppure 0 se manca. */
    static int activeSnapshot(Map<?, ?> info) {
        Object n = info.get(92L);
        return n instanceof Long ? ((Long) n).intValue() + 1 : 0;
    }

    /** Il blob del preset contiene tre valori MessagePack; i nomi sono in [3][10][10][i][4] della mappa finale (chiavi 10, 10, i, 4). */
    static String[] snapshotNames(byte[] blob) throws IOException {
        List<Object> v = MsgPack.decodeAll(blob);
        String[] out = new String[3];
        try {
            Map<?, ?> top = (Map<?, ?>) v.get(2);
            List<?> snaps = (List<?>) ((Map<?, ?>) top.get(10L)).get(10L);
            for (int i = 0; i < 3 && i < snaps.size(); i++) out[i] = text(((Map<?, ?>) snaps.get(i)).get(4L));
        } catch (RuntimeException e) {
            throw new IOException("blob del preset non riconosciuto");
        }
        return out;
    }

    private static String text(Object o) {
        if (!(o instanceof byte[])) return "";
        byte[] n = (byte[]) o;
        int len = n.length;
        while (len > 0 && n[len - 1] == 0) len--;
        return new String(n, 0, len, StandardCharsets.UTF_8);
    }

    /** Smaltisce per ms millisecondi le trame in arrivo; gli eventi della sessione C vengono confermati e scritti nel registro. */
    public void poll(long ms) throws IOException {
        link.pumpFor(ms);
        Message m;
        while ((m = c.poll()) != null) {
            try { log.line("evento: " + MsgPack.toText(MsgPack.decode(m.data))); }
            catch (RuntimeException e) { log.line("evento non decodificato"); }
        }
    }

    public void close() {
        try {
            a.sendAck();
            a.closeFinal();
            b.closeFinal();
            c.closeFinal();
        } catch (IOException ignored) { }
    }

    private Map<Object, Object> ask(Session s, int cmd, long... args) throws IOException {
        int id = s.sendRequest(cmd, args.length == 0 && !emptyMap(cmd) ? null : args);
        Message m = s.await(TIMEOUT, "risposta al comando " + cmd);
        Object o = MsgPack.decode(m.data);
        if (!(o instanceof Map)) throw new IOException("risposta non valida al comando " + cmd);
        @SuppressWarnings("unchecked") Map<Object, Object> r = (Map<Object, Object>) o;
        if (!Long.valueOf(id).equals(r.get(102L))) throw new IOException("id risposta diverso da " + id);
        Object st = r.get(103L);
        boolean ok = Long.valueOf(0).equals(st) || (cmd == 20 && Long.valueOf(1).equals(st));
        if (!ok) throw new IOException("comando " + cmd + ": stato " + st);
        return r;
    }

    /** Comandi che HX Edit invia con argomenti {} (mappa vuota) invece di nil. */
    private static boolean emptyMap(int cmd) { return cmd == 76 || cmd == 99 || cmd == 254; }

    /** Record: {chiave: {109: nome, ...}}; risultato in chiave 104. */
    public static List<Preset> parsePresets(Map<Object, Object> reply) throws IOException {
        Object res = reply.get(104L);
        if (!(res instanceof List)) throw new IOException("elenco preset assente");
        List<Preset> out = new ArrayList<>();
        int pos = 0;
        for (Object rec : (List<?>) res) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rec).entrySet()) {
                byte[] n = (byte[]) ((Map<?, ?>) e.getValue()).get(109L);
                int len = n.length;
                while (len > 0 && n[len - 1] == 0) len--;
                out.add(new Preset(pos++, ((Long) e.getKey()).intValue(), new String(n, 0, len, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }
}
