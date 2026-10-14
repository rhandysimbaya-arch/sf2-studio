package com.sf2studio.sf2;

import java.util.ArrayList;
import java.util.List;

/**
 * A preset zone (refers to an Instrument) or an instrument zone (refers to a Sample).
 * A zone with neither reference is the global zone. The terminal instrument/sampleID
 * generator is not stored in {@link #gens}; it is implied by the reference.
 */
public final class Zone {
    public final List<Gen> gens = new ArrayList<>();
    public final List<Mod> mods = new ArrayList<>();
    public Sample sample;
    public Instrument instrument;

    public boolean isGlobal() {
        return sample == null && instrument == null;
    }

    public Gen find(int oper) {
        for (int i = gens.size() - 1; i >= 0; i--) {
            if (gens.get(i).oper == oper) return gens.get(i);
        }
        return null;
    }

    public void set(int oper, int amount) {
        remove(oper);
        gens.add(new Gen(oper, amount));
    }

    public void remove(int oper) {
        for (int i = gens.size() - 1; i >= 0; i--) {
            if (gens.get(i).oper == oper) gens.remove(i);
        }
    }

    /** Deep copy of generators and modulators; sample/instrument references are shared. */
    public Zone copy() {
        Zone z = new Zone();
        for (Gen g : gens) z.gens.add(g.copy());
        for (Mod m : mods) z.mods.add(m.copy());
        z.sample = sample;
        z.instrument = instrument;
        return z;
    }
}
