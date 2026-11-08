package it.helixpresets.protocol;

/** Messaggio riassemblato (sezione 2): intestazione da 8 byte + dati. */
public final class Message {
    public final int dev, flag, id;
    public final byte[] data;

    Message(int dev, int flag, int id, byte[] data) {
        this.dev = dev; this.flag = flag; this.id = id; this.data = data;
    }
}
