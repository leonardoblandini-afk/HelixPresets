package it.helixpresets.protocol;

/** Trama USB. Tracciato: sezione 1 del documento (testata fissa da 8 byte, sotto-testata, ack, payload). */
public final class Frame {
    public static final int CLASS_OPEN = 0x28, CLASS_DATA = 0x18;
    public static final int TYPE_OPEN = 0x02, TYPE_DATA = 0x04, TYPE_ACK = 0x08, TYPE_DATA_ACK = 0x0c, TYPE_CLOSE = 0x10;
    public static final long HOST_BASE = 0x1000;
    public static final long OPEN_PARAMS_HOST = 0x21000100L; // byte 00 01 00 21 (non interpretati)

    public final int cls, src, dst, seq, type;
    public final long ack;
    public final byte[] payload;

    public Frame(int cls, int src, int dst, int seq, int type, long ack, byte[] payload) {
        this.cls = cls; this.src = src; this.dst = dst; this.seq = seq & 0xff; this.type = type;
        this.ack = ack & 0xffffffffL; this.payload = payload;
    }

    public boolean isData() {
        return type == TYPE_DATA || type == TYPE_DATA_ACK;
    }

    public byte[] toBytes() {
        int l = 8 + payload.length;               // sotto-testata (4) + ack (4) + payload
        byte[] b = new byte[(l + 8 + 3) & ~3];    // riempimento a multiplo di 4 (zeri)
        put16(b, 0, l);
        b[3] = (byte) cls;
        put16(b, 4, src);
        put16(b, 6, dst);
        b[9] = (byte) seq;
        b[11] = (byte) type;
        put32(b, 12, ack);
        System.arraycopy(payload, 0, b, 16, payload.length);
        return b;
    }

    public static Frame parse(byte[] b, int len) {
        if (len < 16) throw new IllegalArgumentException("trama corta: " + len);
        int l = get16(b, 0);
        if (l < 8 || 8 + l > len) throw new IllegalArgumentException("lunghezza L=" + l + " con " + len + " byte");
        byte[] p = new byte[l - 8];
        System.arraycopy(b, 16, p, 0, p.length);
        return new Frame(b[3] & 0xff, get16(b, 4), get16(b, 6), b[9] & 0xff, b[11] & 0xff, get32(b, 12), p);
    }

    public static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    public static void put32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    public static int get16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    public static long get32(byte[] b, int o) {
        long v = 0;
        for (int i = 3; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x ", x & 0xff));
        return s.toString().trim();
    }
}
