package it.helixpresets.protocol;

import java.io.IOException;

/** Canale grezzo: una trama per chiamata. Su Android è l'endpoint bulk 1 (OUT) e 0x81 (IN). */
public interface Transport {
    void write(byte[] frame) throws IOException;

    /** Una trama ricevuta, oppure null se scade il timeout. */
    byte[] read(int timeoutMs) throws IOException;
}
