package com.sf2studio.sf2;

import java.io.IOException;
import java.io.OutputStream;

/** Sample data that stays in the source .sf2 file and is streamed when needed. */
public final class FileSampleData extends SampleData {
    private final Source src;
    private final long off16;
    private final long off24; // -1 when the file has no sm24 chunk
    private final int len;

    public FileSampleData(Source src, long off16, long off24, int len) {
        this.src = src;
        this.off16 = off16;
        this.off24 = off24;
        this.len = len;
    }

    @Override
    public int length() {
        return len;
    }

    @Override
    public boolean has24() {
        return off24 >= 0;
    }

    @Override
    public short[] readShorts(int from, int count) throws IOException {
        byte[] b = new byte[count * 2];
        src.readFully(off16 + 2L * from, b, 0, b.length);
        short[] s = new short[count];
        for (int i = 0; i < count; i++) {
            s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        }
        return s;
    }

    @Override
    protected byte[] read24(int from, int count) throws IOException {
        byte[] b = new byte[count];
        src.readFully(off24 + from, b, 0, count);
        return b;
    }

    /** Fast path: the bytes in the file are already little-endian 16-bit. */
    @Override
    public void write16(OutputStream out) throws IOException {
        byte[] buf = new byte[CHUNK * 2];
        int pos = 0;
        while (pos < len) {
            int c = Math.min(CHUNK, len - pos);
            src.readFully(off16 + 2L * pos, buf, 0, c * 2);
            out.write(buf, 0, c * 2);
            pos += c;
        }
    }
}
