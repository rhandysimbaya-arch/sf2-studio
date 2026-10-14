package com.sf2studio.sf2;

import java.io.IOException;
import java.io.OutputStream;

/** PCM data of one sample: 16-bit points, optionally with an extra low byte per point (24-bit). */
public abstract class SampleData {
    protected static final int CHUNK = 32768;

    /** Number of sample points. */
    public abstract int length();

    public abstract boolean has24();

    /** Reads {@code count} 16-bit points starting at {@code from}. */
    public abstract short[] readShorts(int from, int count) throws IOException;

    /** Reads the low (sm24) bytes for {@code count} points. Only valid when {@link #has24()}. */
    protected abstract byte[] read24(int from, int count) throws IOException;

    /** Writes length()*2 bytes, little-endian 16-bit. */
    public void write16(OutputStream out) throws IOException {
        int n = length();
        byte[] buf = new byte[CHUNK * 2];
        int pos = 0;
        while (pos < n) {
            int c = Math.min(CHUNK, n - pos);
            short[] s = readShorts(pos, c);
            for (int i = 0; i < c; i++) {
                buf[2 * i] = (byte) s[i];
                buf[2 * i + 1] = (byte) (s[i] >> 8);
            }
            out.write(buf, 0, c * 2);
            pos += c;
        }
    }

    /** Writes length() bytes of sm24 data (zeros when this sample has no 24-bit part). */
    public void write24(OutputStream out) throws IOException {
        int n = length();
        int pos = 0;
        if (!has24()) {
            byte[] z = new byte[CHUNK];
            while (pos < n) {
                int c = Math.min(CHUNK, n - pos);
                out.write(z, 0, c);
                pos += c;
            }
            return;
        }
        while (pos < n) {
            int c = Math.min(CHUNK, n - pos);
            out.write(read24(pos, c), 0, c);
            pos += c;
        }
    }
}
