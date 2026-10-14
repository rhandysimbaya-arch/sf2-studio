package com.sf2studio.sf2;

/** One sample header plus its (lazily loaded) PCM data. Loop points are relative to the sample start. */
public final class Sample {
    public String name = "Sample";
    public SampleData data = new MemSampleData(new short[0], null);
    public int sampleRate = 44100;
    public int originalPitch = 60; // MIDI key, 255 = unpitched
    public int pitchCorrection;    // cents, signed
    public int sampleType = 1;     // 1 mono, 2 right, 4 left, 8 linked
    public Sample link;            // partner for stereo/linked samples
    public int loopStart;
    public int loopEnd;

    public int length() {
        return data.length();
    }
}
