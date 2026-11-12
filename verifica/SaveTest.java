import it.helixpresets.protocol.*;

import java.nio.file.*;
import java.util.*;

/** Confronta il comando 71 (salvataggio) generato dall'app con i due della cattura del volume. */
public class SaveTest {
    public static void main(String[] a) throws Exception {
        int uguali = 0, totali = 0;
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
            if (!(o instanceof Map) || !Long.valueOf(71).equals(((Map<?, ?>) o).get(100L))) continue;
            Map<?, ?> m = (Map<?, ?>) o, args = (Map<?, ?>) m.get(101L);
            totali++;
            byte[] mine = MsgPack.savePreset(((Long) m.get(102L)).intValue(), ((Long) args.get(107L)).intValue(),
                    ((Long) args.get(108L)).intValue(), (byte[]) args.get(109L));
            if (Arrays.equals(data, mine)) uguali++;
            System.out.println("comando 71: banco " + args.get(107L) + ", posizione " + args.get(108L) + ", nome " + MsgPack.toText(args.get(109L)));
        }
        System.out.println("comandi 71: " + totali + ", byte identici: " + uguali + "/" + totali);
        System.exit(totali == 2 && uguali == totali ? 0 : 1);
    }
}
