import it.helixpresets.protocol.*;

import java.nio.file.*;
import java.util.*;

/** Confronta il comando di cambio snapshot (88) e le conferme (comando 23, chiave 92) con la cattura. */
public class SnapshotTest {
    public static void main(String[] a) throws Exception {
        List<Integer> richieste = new ArrayList<>(), conferme = new ArrayList<>(), eventi = new ArrayList<>();
        int uguali = 0, totali = 0;
        for (String blk : new String(Files.readAllBytes(Paths.get(a[0]))).trim().split("\n\n")) {
            String[] t = blk.trim().split("\\s+");
            byte[] b = new byte[t.length];
            for (int i = 0; i < t.length; i++) b[i] = (byte) Integer.parseInt(t[i], 16);
            Frame f = Frame.parse(b, b.length);
            if (!f.isData() || f.payload.length < 8) continue;
            int len = (int) Frame.get32(f.payload, 4);
            if (f.payload.length != 8 + len) continue;                 // solo messaggi in una trama
            byte[] data = Arrays.copyOfRange(f.payload, 8, 8 + len);
            Object o;
            try { o = MsgPack.decode(data); } catch (RuntimeException e) { continue; }
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            boolean host = (f.src >> 8) == 0x10;
            if (host && Long.valueOf(88).equals(m.get(100L))) {
                int n = ((Long) ((Map<?, ?>) m.get(101L)).get(92L)).intValue();
                int id = ((Long) m.get(102L)).intValue();
                richieste.add(n);
                totali++;
                if (Arrays.equals(data, MsgPack.request(id, 88, 92, n))) uguali++;
            } else if (!host && m.get(104L) instanceof Map && ((Map<?, ?>) m.get(104L)).containsKey(92L)) {
                conferme.add(((Long) ((Map<?, ?>) m.get(104L)).get(92L)).intValue());
            } else if (!host && Long.valueOf(42).equals(m.get(105L))) {
                eventi.add(((Long) ((Map<?, ?>) m.get(106L)).get(92L)).intValue());
            }
        }
        System.out.println("comandi 88 nella cattura: " + richieste + ", byte identici a quelli generati dall'app: " + uguali + "/" + totali);
        System.out.println("risposte al comando 23, chiave 92: " + conferme);
        System.out.println("eventi tipo 42: " + eventi);
        boolean ok = richieste.equals(Arrays.asList(1, 2, 0)) && uguali == totali && conferme.equals(Arrays.asList(1, 2, 0));
        System.exit(ok ? 0 : 1);
    }
}
