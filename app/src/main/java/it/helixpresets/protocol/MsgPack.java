package it.helixpresets.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MessagePack minimo, solo i tipi visti nel log. Interi -> Long, float -> Double, stringhe e blob -> byte[]
 * (le stringhe del dispositivo hanno il NUL finale), array -> List, mappe -> LinkedHashMap.
 */
public final class MsgPack {
    private final byte[] b;
    private int i;

    private MsgPack(byte[] b) { this.b = b; }

    /** Decodifica un solo valore; lancia eccezione se avanzano byte. */
    public static Object decode(byte[] data) {
        MsgPack m = new MsgPack(data);
        Object v = m.read();
        if (m.i != data.length) throw new IllegalArgumentException("restano " + (data.length - m.i) + " byte");
        return v;
    }

    private int u8() { return b[i++] & 0xff; }
    private long un(int n) { long v = 0; for (int k = 0; k < n; k++) v = (v << 8) | u8(); return v; }
    private long sn(int n) { long v = un(n); int sh = 64 - 8 * n; return (v << sh) >> sh; }
    private byte[] raw(int n) { byte[] r = new byte[n]; System.arraycopy(b, i, r, 0, n); i += n; return r; }

    private List<Object> array(int n) {
        List<Object> a = new ArrayList<>(n);
        for (int k = 0; k < n; k++) a.add(read());
        return a;
    }

    private Map<Object, Object> map(int n) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < n; k++) { Object key = read(); m.put(key, read()); }
        return m;
    }

    private Object read() {
        int t = u8();
        if (t < 0x80) return (long) t;
        if (t >= 0xe0) return (long) (byte) t;
        if (t <= 0x8f) return map(t & 15);
        if (t <= 0x9f) return array(t & 15);
        if (t <= 0xbf) return raw(t & 31);
        switch (t) {
            case 0xc0: return null;
            case 0xc2: return Boolean.FALSE;
            case 0xc3: return Boolean.TRUE;
            case 0xca: return (double) Float.intBitsToFloat((int) un(4));
            case 0xcb: return Double.longBitsToDouble(un(8));
            case 0xcc: return un(1);
            case 0xcd: return un(2);
            case 0xce: return un(4);
            case 0xcf: return un(8);
            case 0xd0: return sn(1);
            case 0xd1: return sn(2);
            case 0xd2: return sn(4);
            case 0xd3: return sn(8);
            case 0xc4: case 0xd9: return raw((int) un(1));
            case 0xc5: case 0xda: return raw((int) un(2));
            case 0xc6: case 0xdb: return raw((int) un(4));
            case 0xdc: return array((int) un(2));
            case 0xde: return map((int) un(2));
            default: throw new IllegalArgumentException(String.format("tipo MessagePack 0x%02x a %d", t, i - 1));
        }
    }

    // ---- codifica delle richieste ----

    private static void uint(ByteArrayOutputStream o, long v) {
        if (v < 0x80) o.write((int) v);
        else if (v < 0x100) { o.write(0xcc); o.write((int) v); }
        else { o.write(0xcd); o.write((int) (v >> 8)); o.write((int) v); }
    }

    /** Richiesta: {102: id, 100: comando, 101: argomenti}; args = coppie chiave,valore oppure null (nil). */
    public static byte[] request(int requestId, int command, long... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x83);
        o.write(0x66); uint(o, requestId);
        o.write(0x64); uint(o, command);
        o.write(0x65);
        if (args == null) o.write(0xc0);
        else {
            o.write(0x80 | (args.length / 2));
            for (long a : args) uint(o, a);
        }
        return o.toByteArray();
    }

    /** Testo leggibile di un valore decodificato (per il registro). */
    public static String toText(Object o) {
        if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            int n = b.length;
            while (n > 0 && b[n - 1] == 0) n--;
            boolean text = n > 0;
            for (int k = 0; k < n; k++) if (b[k] < 32 || b[k] > 126) text = false;
            return text ? "\"" + new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1) + "\"" : "<" + b.length + " byte>";
        }
        if (o instanceof Map) {
            StringBuilder s = new StringBuilder("{");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (s.length() > 1) s.append(", ");
                s.append(toText(e.getKey())).append(": ").append(toText(e.getValue()));
            }
            return s.append('}').toString();
        }
        if (o instanceof List) return "[" + ((List<?>) o).size() + " voci]";
        return String.valueOf(o);
    }
}
