package com.sf2studio.sf2;

/** One modulator record, kept verbatim. */
public final class Mod {
    public int src;
    public int dest;
    public int amount; // signed 16-bit
    public int amtSrc;
    public int trans;

    public Mod copy() {
        Mod m = new Mod();
        m.src = src;
        m.dest = dest;
        m.amount = amount;
        m.amtSrc = amtSrc;
        m.trans = trans;
        return m;
    }
}
