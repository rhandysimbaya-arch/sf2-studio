package com.sf2studio.sf2;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** In-memory model of a SoundFont 2 file. Sample PCM stays on disk until written. */
public final class SoundFont {
    public String name = "Untitled";
    public String engine = "EMU8000";
    /** Optional INFO strings (ICRD, IENG, IPRD, ICOP, ICMT, ISFT). */
    public final LinkedHashMap<String, String> info = new LinkedHashMap<>();
    public final List<Preset> presets = new ArrayList<>();
    public final List<Instrument> instruments = new ArrayList<>();
    public final List<Sample> samples = new ArrayList<>();

    /**
     * Remembers which instruments/samples here were created from which imported source objects, so
     * importing more presets from the same file reuses them instead of duplicating PCM. Never saved.
     */
    public final IdentityHashMap<Object, Object> importCache = new IdentityHashMap<>();

    public Preset findPreset(int bank, int program) {
        for (Preset p : presets) {
            if (p.bank == bank && p.program == program) return p;
        }
        return null;
    }
}
