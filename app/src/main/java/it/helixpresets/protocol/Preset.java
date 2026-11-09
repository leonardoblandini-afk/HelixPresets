package it.helixpresets.protocol;

/** Voce dell'elenco: la posizione nell'elenco è il valore usato dal comando 20 (cambio preset). */
public final class Preset {
    public final int position;  // 0, 1, 2… nell'ordine in cui la HX Stomp invia l'elenco
    public final int key;       // numero che accompagna il record (0, 17, 51…), non è la posizione
    public final String name;

    public Preset(int position, int key, String name) { this.position = position; this.key = key; this.name = name; }

    @Override public String toString() {
        return String.format("%03d   %s", position, name.isEmpty() ? "(vuoto)" : name);
    }
}
