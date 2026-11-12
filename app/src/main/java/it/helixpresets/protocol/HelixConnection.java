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
    private static final long TIMEOUT = 2500;

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
    /** Blocco amp acceso: posizione nell'elenco dei blocchi (chiave 98 del comando 30), modello e volume 0..1 (parametro 5). */
    public static final class Amp {
        public final int block, model;
        public final float volume;
        Amp(int block, int model, float volume) { this.block = block; this.model = model; this.volume = volume; }
    }

    /** Modelli di blocco riconosciuti come amp. Per ora solo il 17 (visto nelle catture, confermato come AMP dall'utente). */
    static final java.util.Set<Long> AMP_MODELS = new java.util.HashSet<>(java.util.Arrays.asList(17L));
    public static final int VOLUME_PARAM = 5;

    /** Amp attualmente acceso (chiave 10 = true) e valore del suo volume. null se nessun amp è acceso o il blob non è riconosciuto. */
    public volatile Amp amp;

    public static final class PresetInfo {
        public final String name;
        public final String[] snapshots;
        /** Snapshot attivo (1..3), 0 se non noto. */
        public final int snapshot;
        /** Amp attivo nello snapshot corrente, null se non ce n'è uno acceso. */
        public final Amp amp;

        PresetInfo(String name, String[] snapshots, int snapshot, Amp amp) {
            this.name = name; this.snapshots = snapshots; this.snapshot = snapshot; this.amp = amp;
        }
    }

    /**
     * Preseleziona il preset alla posizione indicata (comando 20 sulla sessione B), poi legge come HX Edit
     * il nome (comando 23) e il preset completo (comando 22) per ricavare i nomi degli snapshot.
     */
    public PresetInfo select(int position) throws IOException {
        try {
            return selectOnce(position);
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith("timeout")) throw e;
            // Il comando è ripetibile senza danni: si smaltiscono le trame in arrivo e si riprova una volta.
            log.line("-- " + e.getMessage() + " -> nuovo tentativo");
            b.sendAck();
            poll(600);
            return selectOnce(position);
        }
    }

    private PresetInfo selectOnce(int position) throws IOException {
        log.line("-- cambio preset, posizione " + position);
        ask(b, 20, 107, 0, 108, position);   // la risposta ha stato 1; l'esito (stato 0) arriva come evento sulla sessione C
        b.sendAck();
        waitEvent(20, 40, 3000);          // fine del caricamento del preset
        Map<Object, Object> cur = ask(b, 23);
        Map<Object, Object> full = ask(b, 22);
        b.sendAck();
        amp = findAmp((byte[]) full.get(104L));
        Map<?, ?> info = (Map<?, ?>) cur.get(104L);
        return new PresetInfo(text(info.get(109L)), snapshotNames((byte[]) full.get(104L)), activeSnapshot(info),
                amp);
    }

    /**
     * Cambio snapshot (1..3): comando 88 con {92: n}, n da 0 a 2, sulla sessione B. La risposta ha stato 0 e i
     * cambiamenti arrivano come eventi sulla sessione C. Poi, come HX Edit, si rilegge il preset corrente (comando 23),
     * dove la chiave 92 è lo snapshot attivo. Restituisce lo snapshot attivo dopo il cambio.
     */
    /**
     * Imposta un parametro di un blocco del preset corrente (comando 30). value da 0 a 1 (sul display 0..10 è il valore diviso 10).
     * Verificato sulla cattura solo per il volume dell'amp (parametro 5); gli altri parametri non sono verificati.
     */
    public void setParameter(int block, int param, float value) throws IOException {
        if (value < 0f || value > 1f) throw new IOException("valore da 0 a 1");
        int id = b.sendParameter(block, param, value);
        reply(b, id, 30);
        b.sendAck();
        poll(50);
    }

    /**
     * Salva il preset corrente sulla HX Stomp (comando 71, come il "Save" di HX Edit). Banco, posizione e nome sono quelli che il
     * dispositivo stesso riporta come preset corrente (comando 23), così si salva sempre sul preset realmente selezionato.
     * Restituisce true se dopo il salvataggio il preset non risulta più modificato (chiave 117 = false).
     */
    public boolean saveCurrentPreset() throws IOException {
        Map<?, ?> cur = (Map<?, ?>) ask(b, 23).get(104L);
        Object bank = cur.get(107L), pos = cur.get(108L), name = cur.get(109L);
        if (!(bank instanceof Long) || !(pos instanceof Long) || !(name instanceof byte[]))
            throw new IOException("preset corrente non riconosciuto");
        log.line("-- salvataggio preset, posizione " + pos);
        int id = b.sendSave(((Long) bank).intValue(), ((Long) pos).intValue(), (byte[]) name);
        reply(b, id, 71);
        b.sendAck();
        waitEvent(6, 100, 1500);           // evento 6 {.., 109: nome} dopo il salvataggio
        Map<?, ?> after = (Map<?, ?>) ask(b, 23).get(104L);
        return !Boolean.TRUE.equals(after.get(117L));
    }

    public int selectSnapshot(int snapshot) throws IOException {
        if (snapshot < 1 || snapshot > 3) throw new IOException("snapshot da 1 a 3");
        log.line("-- cambio snapshot " + snapshot);
        ask(b, 88, 92, snapshot - 1);
        b.sendAck();
        waitEvent(42, 40, 1500);          // evento 42 {92: n} = snapshot cambiato
        Map<Object, Object> cur = ask(b, 23);
        int now = activeSnapshot((Map<?, ?>) cur.get(104L));
        if (now != snapshot) throw new IOException("la HX Stomp è sullo snapshot " + now + " invece di " + snapshot);
        // HX Edit rilegge il preset dopo ogni cambio snapshot: acceso/spento dei blocchi e valori dipendono dallo snapshot.
        Map<Object, Object> full = ask(b, 22);
        b.sendAck();
        amp = findAmp((byte[]) full.get(104L));
        return now;
    }

    /** Nella risposta al comando 23 la chiave 92 è lo snapshot attivo (da 0); qui 1..3, oppure 0 se manca. */
    static int activeSnapshot(Map<?, ?> info) {
        Object n = info.get(92L);
        return n instanceof Long ? ((Long) n).intValue() + 1 : 0;
    }

    /** Il blob del preset contiene tre valori MessagePack; i nomi sono in [3][10][10][i][4] della mappa finale (chiavi 10, 10, i, 4). */
    /**
     * Cerca nel blob del preset (top[0][22] = elenco dei blocchi) il primo blocco di tipo 6 (chiave 19) con modello amp (chiave 9 dei parametri)
     * e acceso (chiave 10 = true). Il volume è top[0][22][blocco][20][11][4][5]. Lo stesso indice (chiave 98) vale per il comando 30.
     */
    public static Amp findAmp(byte[] blob) {
        try {
            Map<?, ?> top = (Map<?, ?>) MsgPack.decodeAll(blob).get(2);
            List<?> blocks = (List<?>) ((Map<?, ?>) top.get(0L)).get(22L);
            for (int i = 0; i < blocks.size(); i++) {
                Map<?, ?> blk = (Map<?, ?>) blocks.get(i);
                if (!Long.valueOf(6).equals(blk.get(19L)) || !(blk.get(20L) instanceof Map)) continue;
                Map<?, ?> par = (Map<?, ?>) blk.get(20L);
                Object model = par.get(9L);
                if (!AMP_MODELS.contains(model) || !Boolean.TRUE.equals(par.get(10L))) continue;
                List<?> values = (List<?>) ((Map<?, ?>) par.get(11L)).get(4L);
                Object v = values.get(VOLUME_PARAM);
                if (v instanceof Number) return new Amp(i, ((Long) model).intValue(), ((Number) v).floatValue());
            }
        } catch (RuntimeException e) {
            // blob non riconosciuto: nessun amp
        }
        return null;
    }

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
    /**
     * Aspetta che la HX Stomp finisca l'operazione: l'evento `type` sulla sessione C, seguito da `quietMs` senza altri eventi
     * (HX Edit manda il comando successivo subito dopo l'ultimo evento, circa 0,25 s dopo un cambio preset). Se l'evento non
     * arriva entro maxMs si prosegue comunque, come faceva la attesa fissa precedente.
     */
    private void waitEvent(int type, long quietMs, long maxMs) throws IOException {
        final long start = System.currentTimeMillis();
        final long[] last = { start };
        final boolean[] seen = { false };
        try {
            link.waitFor(() -> {
                Message m;
                while ((m = c.poll()) != null) {
                    last[0] = System.currentTimeMillis();
                    try {
                        Object o = MsgPack.decode(m.data);
                        log.line("evento: " + MsgPack.toText(o));
                        if (o instanceof Map && Long.valueOf(type).equals(((Map<?, ?>) o).get(105L))) seen[0] = true;
                    } catch (RuntimeException e) { log.line("evento non decodificato"); }
                }
                return seen[0] && System.currentTimeMillis() - last[0] >= quietMs;
            }, maxMs, "evento " + type);
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith("timeout")) throw e;
            log.line("-- evento " + type + " non arrivato in " + maxMs + " ms: si prosegue");
        }
        log.line("-- attesa evento " + type + ": " + (System.currentTimeMillis() - start) + " ms");
    }

    public void poll(long ms) throws IOException {
        link.pumpFor(ms);
        Message m;
        while ((m = c.poll()) != null) {
            try { log.line("evento: " + MsgPack.toText(MsgPack.decode(m.data))); }
            catch (RuntimeException e) { log.line("evento non decodificato"); }
        }
    }

    /** Interrompe subito qualunque attesa in corso (da un altro thread); poi si può chiamare close(). */
    public void abort() { link.abort(); }

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
        return reply(s, id, cmd);
    }

    private Map<Object, Object> reply(Session s, int id, int cmd) throws IOException {
        long end = System.currentTimeMillis() + TIMEOUT;
        while (true) {
            long left = end - System.currentTimeMillis();
            Message m = s.await(Math.max(left, 1), "risposta al comando " + cmd + " (id " + id + ")");
            Object o = MsgPack.decode(m.data);
            if (!(o instanceof Map)) throw new IOException("risposta non valida al comando " + cmd);
            @SuppressWarnings("unchecked") Map<Object, Object> r = (Map<Object, Object>) o;
            Object rid = r.get(102L);
            if (rid instanceof Long && (Long) rid < id) {   // risposta tardiva di un comando già scaduto: si scarta
                log.line("risposta vecchia ignorata (id " + rid + ")");
                continue;
            }
            if (!Long.valueOf(id).equals(rid)) throw new IOException("id risposta " + rid + " diverso da " + id);
            Object st = r.get(103L);
            boolean ok = Long.valueOf(0).equals(st) || (cmd == 20 && Long.valueOf(1).equals(st));
            if (!ok) throw new IOException("comando " + cmd + ": stato " + st);
            return r;
        }
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
