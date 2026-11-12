import it.helixpresets.protocol.*;

import java.nio.file.*;
import java.util.*;

/** Confronta il comando 30 generato dall'app con quelli della cattura del volume (9.3 -> 10). */
public class VolumeTest {
    public static void main(String[] a) throws Exception {
        int uguali = 0, totali = 0;
        List<Double> valori = new ArrayList<>();
        for (String blk : new String(Files.readAllBytes(Paths.get(a[0]))).trim().split("\n\n")) {
            String[] t = blk.trim().split("\\s+");
            byte[] b = new byte[t.length];
            for (int i = 0; i < t.length; i++) b[i] = (byte) Integer.parseInt(t[i], 16);
            Frame f = Frame.parse(b, b.length);
            if (!f.isData() || f.payload.length < 8 || (f.src >> 8) != 0x10) continue;
            int len = (int) Frame.get32(f.payload, 4);
            if (f.payload.length != 8 + len) continue;
            byte[] data = Arrays.copyOfRange(f.payload, 8, 8 + len);
            Object o;
            try { o = MsgPack.decode(data); } catch (RuntimeException e) { continue; }
            if (!(o instanceof Map) || !Long.valueOf(30).equals(((Map<?, ?>) o).get(100L))) continue;
            Map<?, ?> m = (Map<?, ?>) o, args = (Map<?, ?>) m.get(101L);
            int id = ((Long) m.get(102L)).intValue();
            float v = ((Double) args.get(119L)).floatValue();
            valori.add((double) v);
            totali++;
            if (Arrays.equals(data, MsgPack.setParameter(id, ((Long) args.get(98L)).intValue(), ((Long) args.get(28L)).intValue(), v))) uguali++;
        }
        System.out.println("comandi 30: " + totali + ", byte identici: " + uguali + "/" + totali + ", valori " + valori);
        System.exit(totali > 0 && uguali == totali ? 0 : 1);
    }
}
