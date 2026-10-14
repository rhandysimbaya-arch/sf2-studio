package com.sf2studio.sf2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Parses a SoundFont 2 file. Sample PCM is not loaded; samples keep a pointer into the source. */
public final class Sf2Reader {
    private Sf2Reader() {}

    public static SoundFont read(Source src) throws IOException {
        long fileLen = src.length();
        if (fileLen < 12) throw new IOException("Not a SoundFont file (too small).");
        byte[] h = new byte[12];
        src.readFully(0, h, 0, 12);
        if (!tag(h, 0).equals("RIFF") || !tag(h, 8).equals("sfbk")) {
            throw new IOException("Not a SoundFont 2 (.sf2) file.");
        }
        long end = Math.min(fileLen, 8 + u32(h, 4));

        SoundFont sf = new SoundFont();
        long smplPos = -1, smplSize = 0, sm24Pos = -1, sm24Size = 0;
        Map<String, byte[]> pdta = new HashMap<>();

        long pos = 12;
        byte[] ch = new byte[12];
        while (pos + 8 <= end) {
            src.readFully(pos, ch, 0, 8);
            String id = tag(ch, 0);
            long size = u32(ch, 4);
            long bodyEnd = Math.min(end, pos + 8 + size);
            if (id.equals("LIST") && pos + 12 <= end) {
                src.readFully(pos + 8, ch, 8, 4);
                String type = tag(ch, 8);
                long p = pos + 12;
                byte[] sub = new byte[8];
                while (p + 8 <= bodyEnd) {
                    src.readFully(p, sub, 0, 8);
                    String cid = tag(sub, 0);
                    long csz = u32(sub, 4);
                    long dpos = p + 8;
                    long avail = Math.max(0, Math.min(bodyEnd, dpos + csz) - dpos);
                    if (type.equals("INFO")) {
                        if (avail <= (1 << 20)) readInfo(sf, cid, load(src, dpos, avail));
                    } else if (type.equals("sdta")) {
                        if (cid.equals("smpl")) {
                            smplPos = dpos;
                            smplSize = avail;
                        } else if (cid.equals("sm24")) {
                            sm24Pos = dpos;
                            sm24Size = avail;
                        }
                    } else if (type.equals("pdta")) {
                        pdta.put(cid, load(src, dpos, avail));
                    }
                    p = dpos + csz + (csz & 1);
                }
            }
            pos = pos + 8 + size + (size & 1);
        }

        byte[] phdr = need(pdta, "phdr");
        byte[] pbag = need(pdta, "pbag");
        byte[] pmod = need(pdta, "pmod");
        byte[] pgen = need(pdta, "pgen");
        byte[] inst = need(pdta, "inst");
        byte[] ibag = need(pdta, "ibag");
        byte[] imod = need(pdta, "imod");
        byte[] igen = need(pdta, "igen");
        byte[] shdr = need(pdta, "shdr");

        // ---- samples ----
        long smplPoints = smplPos < 0 ? 0 : smplSize / 2;
        boolean use24 = sm24Pos >= 0 && sm24Size >= smplPoints;
        int ns = Math.max(0, shdr.length / 46 - 1);
        Sample[] samples = new Sample[ns];
        int[] links = new int[ns];
        for (int i = 0; i < ns; i++) {
            int o = i * 46;
            Sample s = new Sample();
            s.name = str(shdr, o, 20);
            long start = u32(shdr, o + 20);
            long endp = u32(shdr, o + 24);
            long sl = u32(shdr, o + 28);
            long el = u32(shdr, o + 32);
            s.sampleRate = (int) u32(shdr, o + 36);
            s.originalPitch = shdr[o + 40] & 0xFF;
            s.pitchCorrection = shdr[o + 41]; // signed byte
            links[i] = u16(shdr, o + 42);
            int type = u16(shdr, o + 44);
            if ((type & 0x10) != 0) {
                throw new UnsupportedOperationException(
                        "This SoundFont uses compressed (SF3 / Ogg Vorbis) samples, which are not supported.");
            }
            long len = endp - start;
            boolean bad = smplPos < 0 || (type & 0x8000) != 0 || endp < start
                    || endp > smplPoints || len > Integer.MAX_VALUE;
            if (bad) {
                s.data = new MemSampleData(new short[0], null);
                s.sampleType = type & 0x7FFF & 0xF;
                if (s.sampleType == 0) s.sampleType = 1;
            } else {
                s.data = new FileSampleData(src, smplPos + start * 2, use24 ? sm24Pos + start : -1, (int) len);
                s.sampleType = type & 0xF;
                if (s.sampleType == 0) s.sampleType = 1;
                s.loopStart = (int) Math.max(0, Math.min(len, sl - start));
                s.loopEnd = (int) Math.max(0, Math.min(len, el - start));
            }
            samples[i] = s;
        }
        for (int i = 0; i < ns; i++) {
            Sample s = samples[i];
            if ((s.sampleType & (2 | 4 | 8)) != 0 && links[i] < ns && links[i] != i) {
                s.link = samples[links[i]];
            } else if ((s.sampleType & (2 | 4 | 8)) != 0) {
                s.sampleType = 1;
            }
        }

        // ---- instruments ----
        int ni = Math.max(0, inst.length / 22 - 1);
        int nIbag = ibag.length / 4;
        int nImod = imod.length / 10;
        int nIgen = igen.length / 4;
        Instrument[] instruments = new Instrument[ni];
        for (int i = 0; i < ni; i++) {
            Instrument in = new Instrument();
            in.name = str(inst, i * 22, 20);
            int bagStart = u16(inst, i * 22 + 20);
            int bagEnd = u16(inst, (i + 1) * 22 + 20);
            for (int b = bagStart; b < bagEnd && b + 1 < nIbag; b++) {
                int genStart = u16(ibag, b * 4);
                int genEnd = Math.min(u16(ibag, (b + 1) * 4), nIgen);
                int modStart = u16(ibag, b * 4 + 2);
                int modEnd = Math.min(u16(ibag, (b + 1) * 4 + 2), nImod);
                Zone z = new Zone();
                boolean bad = false;
                for (int g = genStart; g < genEnd; g++) {
                    int oper = u16(igen, g * 4);
                    int amt = u16(igen, g * 4 + 2);
                    if (oper == Gens.SAMPLE_ID) {
                        if (g == genEnd - 1) {
                            if (amt < ns) z.sample = samples[amt];
                            else bad = true;
                        }
                        continue;
                    }
                    if (oper == Gens.INSTRUMENT) continue;
                    z.gens.add(new Gen(oper, amt));
                }
                for (int m = modStart; m < modEnd; m++) z.mods.add(readMod(imod, m));
                if (bad) continue;
                if (z.sample == null && !in.zones.isEmpty()) continue; // only the first zone may be global
                in.zones.add(z);
            }
            instruments[i] = in;
        }

        // ---- presets ----
        int np = Math.max(0, phdr.length / 38 - 1);
        int nPbag = pbag.length / 4;
        int nPmod = pmod.length / 10;
        int nPgen = pgen.length / 4;
        for (int i = 0; i < np; i++) {
            int o = i * 38;
            Preset p = new Preset();
            p.name = str(phdr, o, 20);
            p.program = u16(phdr, o + 20);
            p.bank = u16(phdr, o + 22);
            int bagStart = u16(phdr, o + 24);
            p.library = u32(phdr, o + 26);
            p.genre = u32(phdr, o + 30);
            p.morphology = u32(phdr, o + 34);
            int bagEnd = u16(phdr, o + 38 + 24);
            for (int b = bagStart; b < bagEnd && b + 1 < nPbag; b++) {
                int genStart = u16(pbag, b * 4);
                int genEnd = Math.min(u16(pbag, (b + 1) * 4), nPgen);
                int modStart = u16(pbag, b * 4 + 2);
                int modEnd = Math.min(u16(pbag, (b + 1) * 4 + 2), nPmod);
                Zone z = new Zone();
                boolean bad = false;
                for (int g = genStart; g < genEnd; g++) {
                    int oper = u16(pgen, g * 4);
                    int amt = u16(pgen, g * 4 + 2);
                    if (oper == Gens.INSTRUMENT) {
                        if (g == genEnd - 1) {
                            if (amt < ni) z.instrument = instruments[amt];
                            else bad = true;
                        }
                        continue;
                    }
                    if (oper == Gens.SAMPLE_ID) continue;
                    z.gens.add(new Gen(oper, amt));
                }
                for (int m = modStart; m < modEnd; m++) z.mods.add(readMod(pmod, m));
                if (bad) continue;
                if (z.instrument == null && !p.zones.isEmpty()) continue;
                p.zones.add(z);
            }
            sf.presets.add(p);
        }

        for (Instrument in : instruments) sf.instruments.add(in);
        for (Sample s : samples) sf.samples.add(s);
        return sf;
    }

    private static Mod readMod(byte[] b, int idx) {
        int o = idx * 10;
        Mod m = new Mod();
        m.src = u16(b, o);
        m.dest = u16(b, o + 2);
        m.amount = (short) u16(b, o + 4);
        m.amtSrc = u16(b, o + 6);
        m.trans = u16(b, o + 8);
        return m;
    }

    private static void readInfo(SoundFont sf, String id, byte[] data) throws IOException {
        switch (id) {
            case "ifil":
                if (data.length >= 4 && u16(data, 0) >= 3) {
                    throw new UnsupportedOperationException(
                            "SoundFont version 3 files are not supported.");
                }
                break;
            case "isng":
                sf.engine = str(data, 0, data.length);
                break;
            case "INAM":
                sf.name = str(data, 0, data.length);
                break;
            case "ICRD":
            case "IENG":
            case "IPRD":
            case "ICOP":
            case "ICMT":
            case "ISFT":
                sf.info.put(id, str(data, 0, data.length));
                break;
            default:
                break;
        }
    }

    private static byte[] need(Map<String, byte[]> m, String id) throws IOException {
        byte[] b = m.get(id);
        if (b == null) throw new IOException("Damaged SoundFont: missing '" + id + "' chunk.");
        return b;
    }

    private static byte[] load(Source src, long pos, long len) throws IOException {
        if (len < 0 || len > Integer.MAX_VALUE - 16) throw new IOException("Chunk too large.");
        byte[] b = new byte[(int) len];
        if (b.length > 0) src.readFully(pos, b, 0, b.length);
        return b;
    }

    private static String tag(byte[] b, int o) {
        return new String(b, o, 4, StandardCharsets.ISO_8859_1);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static String str(byte[] b, int o, int max) {
        int n = 0;
        while (n < max && o + n < b.length && b[o + n] != 0) n++;
        return new String(b, o, n, StandardCharsets.ISO_8859_1);
    }
}
