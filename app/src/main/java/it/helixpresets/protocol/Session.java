package it.helixpresets.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Una sessione (coppia id host / id dispositivo). Gestisce seq, ack e il riassemblaggio dei messaggi.
 * Valori e ordine delle trame ricavati dalla cattura; vedi il documento "Tracciato record".
 */
public final class Session {
    private final Link link;
    public final int host, dev;
    private int seq, channel;
    private long recv, lastAck;
    private boolean openReply, closeReply;
    private final ArrayDeque<Message> inbox = new ArrayDeque<>();
    private ByteArrayOutputStream buf;
    private int curFlag, curId, curLen;
    private int nextRequestId = 1000;
    private boolean autoAck;

    Session(Link link, int host, int dev) { this.link = link; this.host = host; this.dev = dev; }

    /** Per la sessione degli eventi: ogni messaggio completo riceve subito un ack. */
    void setAutoAck(boolean on) { autoAck = on; }

    // ---------- invio ----------

    private long ack() { return Frame.HOST_BASE + recv; }

    private void send(int type, long ack, byte[] payload) throws IOException {
        link.write(new Frame(Frame.CLASS_DATA, host, dev, seq, type, ack, payload));
        seq = (seq + 1) & 0xff;
        lastAck = ack;
    }

    public void open() throws IOException {
        seq = 0; recv = 0; lastAck = Frame.HOST_BASE;
        openReply = false; closeReply = false; buf = null; inbox.clear(); nextRequestId = 1000;
        byte[] counter = new byte[4];
        Frame.put32(counter, 0, Frame.HOST_BASE);
        link.write(new Frame(Frame.CLASS_OPEN, host, dev, 0, Frame.TYPE_OPEN, Frame.OPEN_PARAMS_HOST, counter));
        seq = 2; // dopo l'apertura il primo seq usato nel log è 2
        link.waitFor(() -> openReply, 3000, String.format("apertura sessione %04x", dev));
    }

    /** Messaggio host->device: u16 1, u16 id, u32 lunghezza, dati. Una sola trama (max 248 byte di dati). */
    public void sendMessage(int id, byte[] data) throws IOException {
        if (data.length > 248) throw new IOException("messaggio troppo lungo per una trama");
        byte[] p = new byte[8 + data.length];
        Frame.put16(p, 0, 1);
        Frame.put16(p, 2, id);
        Frame.put32(p, 4, data.length);
        System.arraycopy(data, 0, p, 8, data.length);
        long a = ack();
        send(a != lastAck ? Frame.TYPE_DATA_ACK : Frame.TYPE_DATA, a, p);
    }

    /** Primo messaggio della sessione: un byte con l'id scelto. */
    public void hello(int id) throws IOException {
        channel = id;
        sendMessage(id, new byte[] { (byte) id });
    }

    /** Invia un comando con il prossimo id (contatore per sessione da 1000) e restituisce l'id usato. */
    public int sendRequest(int command, long... args) throws IOException {
        int id = nextRequestId++;
        sendMessage(channel, MsgPack.request(id, command, args));
        return id;
    }

    public void sendAck() throws IOException {
        send(Frame.TYPE_ACK, ack(), new byte[0]);
    }

    /** Chiusura "morbida" (tipo 0x02, classe 0x18) vista prima di una riapertura. */
    public void closeSoft() throws IOException {
        send(Frame.TYPE_OPEN, ack(), new byte[0]);
        try {
            link.waitFor(() -> closeReply, 1000, "risposta alla chiusura");
        } catch (IOException ignored) { }
    }

    /** Chiusura finale (tipo 0x10). */
    public void closeFinal() throws IOException {
        send(Frame.TYPE_CLOSE, ack(), new byte[0]);
    }

    // ---------- ricezione ----------

    void onFrame(Frame f) throws IOException {
        if (f.type == Frame.TYPE_OPEN) {
            if (f.cls == Frame.CLASS_OPEN) openReply = true; else closeReply = true;
        } else if (f.isData()) {
            onData(f.payload);
        } // 0x08 (ack del device) e 0x10: nulla da fare
    }

    private void onData(byte[] p) throws IOException {
        recv += p.length;
        int off = 0;
        if (buf == null) {
            if (p.length < 8) return;
            curFlag = Frame.get16(p, 0);
            curId = Frame.get16(p, 2);
            curLen = (int) Frame.get32(p, 4);
            buf = new ByteArrayOutputStream(curLen);
            off = 8;
        }
        buf.write(p, off, p.length - off);
        if (buf.size() >= curLen) {
            inbox.add(new Message(dev, curFlag, curId, Arrays.copyOf(buf.toByteArray(), curLen)));
            buf = null;
            if (autoAck) sendAck();
        } else {
            sendAck(); // come nel log: un ack dopo ogni trama intermedia
        }
    }

    /** Messaggio già arrivato, oppure null. */
    public Message poll() { return inbox.poll(); }

    public Message await(long timeoutMs, String what) throws IOException {
        link.waitFor(() -> !inbox.isEmpty(), timeoutMs, what);
        return inbox.poll();
    }
}
