package com.sf2studio.sf2;

/** Sample data held in memory (imported WAVs, tests). */
public final class MemSampleData extends SampleData {
    private final short[] pcm;
    private final byte[] low; // may be null

    public MemSampleData(short[] pcm, byte[] low) {
        this.pcm = pcm;
        this.low = low;
    }

    @Override
    public int length() {
        return pcm.length;
    }

    @Override
    public boolean has24() {
        return low != null;
    }

    @Override
    public short[] readShorts(int from, int count) {
        short[] r = new short[count];
        System.arraycopy(pcm, from, r, 0, count);
        return r;
    }

    @Override
    protected byte[] read24(int from, int count) {
        byte[] r = new byte[count];
        System.arraycopy(low, from, r, 0, count);
        return r;
    }
}
