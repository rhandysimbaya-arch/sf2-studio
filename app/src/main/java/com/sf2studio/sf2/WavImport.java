package com.sf2studio.sf2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal WAV reader: PCM 8/16/24/32-bit and float32, any channel count (mixed down to mono). */
public final class WavImport {
    private WavImport() {}

    public static final class Wav {
        public short[] pcm;
        public int rate;
        public int rootKey = 60;
        /** Loop in points (end exclusive), or -1 when the file has no loop. */
        public int loopStart = -1;
        public int loopEnd = -1;
    }

    private static final int MAX_BYTES = 160 * 1024 * 1024;

    public static Wav read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > MAX_BYTES) throw new IOException("WAV file is too large (limit 160 MB).");
        }
        byte[] all = bo.toByteArray();
        if (all.length < 12 || !tag(all, 0).equals("RIFF") || !tag(all, 8).equals("WAVE")) {
            throw new IOException("Not a WAV file.");
        }
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);

        int fmtTag = 0, channels = 0, bits = 0, rate = 0;
        int dataPos = -1, dataLen = 0;
        int rootKey = 60, loopStart = -1, loopEnd = -1;

        int pos = 12;
        while (pos + 8 <= all.length) {
            String id = tag(all, pos);
            long size = bb.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            int avail = (int) Math.min(size, all.length - body);
            if (id.equals("fmt ") && avail >= 16) {
                fmtTag = bb.getShort(body) & 0xFFFF;
                channels = bb.getShort(body + 2) & 0xFFFF;
                rate = bb.getInt(body + 4);
                bits = bb.getShort(body + 14) & 0xFFFF;
                if (fmtTag == 0xFFFE && avail >= 26) fmtTag = bb.getShort(body + 24) & 0xFFFF;
            } else if (id.equals("data")) {
                dataPos = body;
                dataLen = avail;
            } else if (id.equals("smpl") && avail >= 36) {
                int note = bb.getInt(body + 12);
                if (note >= 0 && note <= 127) rootKey = note;
                int loops = bb.getInt(body + 28);
                if (loops > 0 && avail >= 36 + 24) {
                    int lo = 36;
                    loopStart = bb.getInt(body + lo + 8);
                    loopEnd = bb.getInt(body + lo + 12) + 1; // WAV end is inclusive
                }
            }
            pos = body + avail + (avail & 1);
        }
        if (dataPos < 0 || channels < 1 || rate <= 0) throw new IOException("Damaged WAV file (no fmt/data chunk).");
        boolean isFloat = fmtTag == 3;
        if (!(fmtTag == 1 || isFloat)) throw new IOException("Unsupported WAV encoding (only PCM and float are supported).");
        if (!(bits == 8 || bits == 16 || bits == 24 || bits == 32)) {
            throw new IOException("Unsupported WAV bit depth: " + bits);
        }
        if (isFloat && bits != 32) throw new IOException("Unsupported WAV float depth: " + bits);

        int bytesPer = bits / 8;
        int frames = dataLen / (bytesPer * channels);
        short[] pcm = new short[frames];
        int p = dataPos;
        for (int f = 0; f < frames; f++) {
            long sum = 0;
            for (int c = 0; c < channels; c++) {
                int v;
                switch (bits) {
                    case 8:
                        v = ((all[p] & 0xFF) - 128) << 8;
                        break;
                    case 16:
                        v = (short) ((all[p] & 0xFF) | (all[p + 1] << 8));
                        break;
                    case 24:
                        v = (short) ((all[p + 1] & 0xFF) | (all[p + 2] << 8));
                        break;
                    default:
                        if (isFloat) {
                            float fl = Float.intBitsToFloat(bb.getInt(p));
                            v = Math.round(Math.max(-1f, Math.min(1f, fl)) * 32767f);
                        } else {
                            v = (short) ((all[p + 2] & 0xFF) | (all[p + 3] << 8));
                        }
                        break;
                }
                sum += v;
                p += bytesPer;
            }
            pcm[f] = (short) (sum / channels);
        }

        Wav w = new Wav();
        w.pcm = pcm;
        w.rate = rate;
        w.rootKey = rootKey;
        if (loopStart >= 0 && loopEnd > loopStart && loopEnd <= frames) {
            w.loopStart = loopStart;
            w.loopEnd = loopEnd;
        }
        return w;
    }

    private static String tag(byte[] b, int o) {
        return new String(b, o, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
