package com.sf2studio.sf2;

import java.util.ArrayList;
import java.util.List;

public final class Preset {
    public String name = "Preset";
    public int bank;
    public int program;
    public long library;
    public long genre;
    public long morphology;
    public final List<Zone> zones = new ArrayList<>();
}
