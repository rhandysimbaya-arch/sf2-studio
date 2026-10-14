package com.sf2studio.sf2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Editing operations: merge/import, delete, clean up, add samples. */
public final class Sf2Ops {
    private Sf2Ops() {}

    public enum Conflict { RENUMBER, SKIP, KEEP_BOTH }

    /** Trims and limits a name to the 20 bytes an SF2 name field holds. */
    public static String clampName(String s) {
        if (s == null) s = "";
        s = s.trim();
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    public static boolean slotFree(SoundFont sf, int bank, int program) {
        return sf.findPreset(bank, program) == null;
    }

    /** Lowest free program in the same bank, else the next banks. Null when nothing is free. */
    public static int[] nextFreeSlot(SoundFont sf, int bank, int program) {
        for (int b = bank; b <= Math.min(16383, bank + 255); b++) {
            for (int p = 0; p < 128; p++) {
                if (slotFree(sf, b, p)) return new int[]{b, p};
            }
        }
        return null;
    }

    /**
     * Copies the chosen presets of {@code src} into {@code dst}, together with only the instruments and
     * samples they need. Sample PCM is shared with {@code src}'s source file, so keep that open until saved.
     *
     * @return number of presets imported
     */
    public static int importPresets(SoundFont dst, SoundFont src, List<Preset> chosen, Conflict policy) {
        int count = 0;
        for (Preset p : chosen) {
            int bank = p.bank;
            int prog = p.program;
            if (dst.findPreset(bank, prog) != null) {
                if (policy == Conflict.SKIP) continue;
                if (policy == Conflict.RENUMBER) {
                    int[] slot = nextFreeSlot(dst, bank, prog);
                    if (slot == null) continue;
                    bank = slot[0];
                    prog = slot[1];
                }
            }
            Preset np = new Preset();
            np.name = p.name;
            np.bank = bank;
            np.program = prog;
            np.library = p.library;
            np.genre = p.genre;
            np.morphology = p.morphology;
            for (Zone z : p.zones) {
                Zone nz = z.copy();
                if (z.instrument != null) nz.instrument = copyInstrument(z.instrument, dst);
                np.zones.add(nz);
            }
            dst.presets.add(np);
            count++;
        }
        return count;
    }

    private static Instrument copyInstrument(Instrument in, SoundFont dst) {
        Object cached = dst.importCache.get(in);
        if (cached instanceof Instrument && dst.instruments.contains(cached)) return (Instrument) cached;
        Instrument ni = new Instrument();
        ni.name = in.name;
        dst.importCache.put(in, ni);
        for (Zone z : in.zones) {
            Zone nz = z.copy();
            if (z.sample != null) nz.sample = copySample(z.sample, dst);
            ni.zones.add(nz);
        }
        dst.instruments.add(ni);
        return ni;
    }

    private static Sample copySample(Sample s, SoundFont dst) {
        Object cached = dst.importCache.get(s);
        if (cached instanceof Sample && dst.samples.contains(cached)) return (Sample) cached;
        Sample ns = new Sample();
        ns.name = s.name;
        ns.data = s.data;
        ns.sampleRate = s.sampleRate;
        ns.originalPitch = s.originalPitch;
        ns.pitchCorrection = s.pitchCorrection;
        ns.sampleType = s.sampleType;
        ns.loopStart = s.loopStart;
        ns.loopEnd = s.loopEnd;
        dst.importCache.put(s, ns);
        dst.samples.add(ns);
        if (s.link != null) ns.link = copySample(s.link, dst);
        return ns;
    }

    public static void deletePreset(SoundFont sf, Preset p) {
        sf.presets.remove(p);
    }

    /** Removes the instrument and every preset zone that used it. */
    public static void deleteInstrument(SoundFont sf, Instrument in) {
        sf.instruments.remove(in);
        for (Preset p : sf.presets) {
            for (int i = p.zones.size() - 1; i >= 0; i--) {
                if (p.zones.get(i).instrument == in) p.zones.remove(i);
            }
        }
    }

    /** Removes the sample and every instrument zone that used it. */
    public static void deleteSample(SoundFont sf, Sample s) {
        sf.samples.remove(s);
        for (Instrument in : sf.instruments) {
            for (int i = in.zones.size() - 1; i >= 0; i--) {
                if (in.zones.get(i).sample == s) in.zones.remove(i);
            }
        }
        for (Sample o : sf.samples) {
            if (o.link == s) {
                o.link = null;
                o.sampleType = 1;
            }
        }
    }

    public static int countUses(SoundFont sf, Instrument in) {
        int n = 0;
        for (Preset p : sf.presets) {
            for (Zone z : p.zones) if (z.instrument == in) n++;
        }
        return n;
    }

    public static int countUses(SoundFont sf, Sample s) {
        int n = 0;
        for (Instrument in : sf.instruments) {
            for (Zone z : in.zones) if (z.sample == s) n++;
        }
        return n;
    }

    /** Drops instruments no preset uses and samples no instrument uses. Returns {instruments, samples}. */
    public static int[] removeUnused(SoundFont sf) {
        Set<Instrument> usedI = Collections.newSetFromMap(new IdentityHashMap<Instrument, Boolean>());
        for (Preset p : sf.presets) {
            for (Zone z : p.zones) if (z.instrument != null) usedI.add(z.instrument);
        }
        int removedI = 0;
        for (int i = sf.instruments.size() - 1; i >= 0; i--) {
            if (!usedI.contains(sf.instruments.get(i))) {
                sf.instruments.remove(i);
                removedI++;
            }
        }
        Set<Sample> usedS = Collections.newSetFromMap(new IdentityHashMap<Sample, Boolean>());
        for (Instrument in : sf.instruments) {
            for (Zone z : in.zones) {
                Sample s = z.sample;
                int guard = 0;
                while (s != null && usedS.add(s) && guard++ < 8) s = s.link;
            }
        }
        int removedS = 0;
        for (int i = sf.samples.size() - 1; i >= 0; i--) {
            if (!usedS.contains(sf.samples.get(i))) {
                sf.samples.remove(i);
                removedS++;
            }
        }
        return new int[]{removedI, removedS};
    }

    /**
     * Adds a mono sample as a new instrument and preset in the first free slot.
     *
     * @param loopStart loop start in points, or &lt; 0 for no loop
     * @param loopEnd   loop end in points
     */
    public static Preset addSampleAsPreset(SoundFont sf, String name, short[] pcm, int rate, int rootKey,
                                           int loopStart, int loopEnd) {
        int[] slot = nextFreeSlot(sf, 0, 0);
        if (slot == null) throw new IllegalStateException("No free preset slot left.");
        String nm = clampName(name);
        if (nm.isEmpty()) nm = "Sample";

        Sample s = new Sample();
        s.name = nm;
        s.data = new MemSampleData(pcm, null);
        s.sampleRate = Math.max(400, rate);
        s.originalPitch = Math.max(0, Math.min(127, rootKey));
        boolean loop = loopStart >= 0 && loopEnd > loopStart && loopEnd <= pcm.length;
        s.loopStart = loop ? loopStart : 0;
        s.loopEnd = loop ? loopEnd : 0;
        sf.samples.add(s);

        Instrument in = new Instrument();
        in.name = nm;
        Zone iz = new Zone();
        iz.sample = s;
        if (loop) iz.gens.add(new Gen(Gens.SAMPLE_MODES, 1));
        in.zones.add(iz);
        sf.instruments.add(in);

        Preset p = new Preset();
        p.name = nm;
        p.bank = slot[0];
        p.program = slot[1];
        Zone pz = new Zone();
        pz.instrument = in;
        p.zones.add(pz);
        sf.presets.add(p);
        return p;
    }

    public static List<Preset> sortedPresets(SoundFont sf) {
        List<Preset> l = new ArrayList<>(sf.presets);
        Collections.sort(l, new java.util.Comparator<Preset>() {
            @Override
            public int compare(Preset a, Preset b) {
                if (a.bank != b.bank) return a.bank < b.bank ? -1 : 1;
                if (a.program != b.program) return a.program < b.program ? -1 : 1;
                return 0;
            }
        });
        return l;
    }
}
