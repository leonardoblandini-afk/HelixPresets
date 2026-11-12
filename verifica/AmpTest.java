import it.helixpresets.protocol.*;

import java.nio.file.*;

/** Cerca l'amp acceso nei tre preset letti dopo i cambi snapshot 2, 3, 1 (cattura snapshot). */
public class AmpTest {
    static byte[] hex(String path) throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(path))).trim();
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] a) throws Exception {
        int[] blocchi = { 3, 3, 2 };                  // snapshot 2, 3, 1
        float[] volumi = { 0.80f, 0.85f, 0.93f };
        boolean ok = true;
        for (int k = 0; k < 3; k++) {
            HelixConnection.Amp amp = HelixConnection.findAmp(hex(a[0] + (k + 1) + ".hex"));
            System.out.println("blob " + (k + 1) + ": " + (amp == null ? "nessun amp" : "amp blocco " + amp.block + ", modello " + amp.model + ", volume " + amp.volume + " (" + amp.volume * 10 + ")"));
            if (amp == null || amp.block != blocchi[k] || Math.abs(amp.volume - volumi[k]) > 1e-6) ok = false;
        }
        System.exit(ok ? 0 : 1);
    }
}
