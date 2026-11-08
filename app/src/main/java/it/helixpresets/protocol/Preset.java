package it.helixpresets.protocol;

public final class Preset {
    public final int slot;
    public final String name;

    public Preset(int slot, String name) { this.slot = slot; this.name = name; }

    @Override public String toString() {
        return String.format("%03d   %s", slot, name.isEmpty() ? "(vuoto)" : name);
    }
}
