package com.sf2studio.sf2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles a {@link SoundFont} into a valid SoundFont 2.04 file, streaming sample data. */
public final class Sf2Writer {
    private Sf2Writer() {}

    public interface Progress {
        void update(double fraction);
    }

    private static final int PAD = 46; // zero points required after every sample

    /**
     * The SF2 format stores zone/generator/modulator indexes as 16-bit numbers. Returns a readable
     * description when the soundfont is too big to be saved, or null when it fits.
     */
    public static String limitProblem(SoundFont sf) {
        long pBag = 0, pGen = 0, pMod = 0, iBag = 0, iGen = 0, iMod = 0;
        for (Preset p : sf.presets) {
            for (Zone z : p.zones) {
                pBag++;
                pGen += orderedGens(z.gens).size() + (z.instrument != null ? 1 : 0);
                pMod += z.mods.size();
            }
        }
        for (Instrument in : sf.instruments) {
            for (Zone z : in.zones) {
                iBag++;
                iGen += orderedGens(z.gens).size() + (z.sample != null ? 1 : 0);
                iMod += z.mods.size();
            }
        }
        String hint = " Remove some presets or instruments, or import fewer presets, so it fits.";
        if (iGen > 0xFFFF) return "Too many instrument settings for the SF2 format (" + iGen + " of 65535 allowed)." + hint;
        if (iBag > 0xFFFF) return "Too many instrument zones for the SF2 format (" + iBag + " of 65535 allowed)." + hint;
        if (iMod > 0xFFFF) return "Too many instrument modulators for the SF2 format (" + iMod + " of 65535 allowed)." + hint;
        if (pGen > 0xFFFF) return "Too many preset settings for the SF2 format (" + pGen + " of 65535 allowed)." + hint;
        if (pBag > 0xFFFF) return "Too many preset zones for the SF2 format (" + pBag + " of 65535 allowed)." + hint;
        if (pMod > 0xFFFF) return "Too many preset modulators for the SF2 format (" + pMod + " of 65535 allowed)." + hint;
        if (sf.samples.size() > 0xFFFF) return "Too many samples for the SF2 format (" + sf.samples.size() + " of 65535 allowed)." + hint;
        if (sf.instruments.size() > 0xFFFF) return "Too many instruments for the SF2 format (" + sf.instruments.size() + " of 65535 allowed)." + hint;
        return null;
    }

    public static void write(SoundFont sf, OutputStream out, Progress progress) throws IOException {
        String problem = limitProblem(sf);
        if (problem != null) throw new IOException(problem);
        IdentityHashMap<Sample, Integer> sIdx = new IdentityHashMap<>();
        for (int i = 0; i < sf.samples.size(); i++) sIdx.put(sf.samples.get(i), i);
        IdentityHashMap<Instrument, Integer> iIdx = new IdentityHashMap<>();
        for (int i = 0; i < sf.instruments.size(); i++) iIdx.put(sf.instruments.get(i), i);

        List<Preset> presets = new ArrayList<>(sf.presets);
        Collections.sort(presets, new Comparator<Preset>() {
            @Override
            public int compare(Preset a, Preset b) {
                if (a.bank != b.bank) return a.bank < b.bank ? -1 : 1;
                if (a.program != b.program) return a.program < b.program ? -1 : 1;
                return 0;
            }
        });

        Le phdr = new Le(), pbag = new Le(), pmod = new Le(), pgen = new Le();
        Le inst = new Le(), ibag = new Le(), imod = new Le(), igen = new Le(), shdr = new Le();

        // ---- presets ----
        int bagCount = 0, genCount = 0, modCount = 0;
        for (Preset p : presets) {
            phdr.str(p.name, 20);
            phdr.u16(p.program);
            phdr.u16(p.bank);
            phdr.u16(bagCount);
            phdr.u32(p.library);
            phdr.u32(p.genre);
            phdr.u32(p.morphology);
            for (Zone z : p.zones) {
                pbag.u16(genCount);
                pbag.u16(modCount);
                for (Gen g : orderedGens(z.gens)) {
                    pgen.u16(g.oper);
                    pgen.u16(g.amount);
                    genCount++;
                }
                if (z.instrument != null) {
                    Integer ix = iIdx.get(z.instrument);
                    if (ix == null) {
                        throw new IOException("Preset '" + p.name + "' uses an instrument that is not in the file.");
                    }
                    pgen.u16(Gens.INSTRUMENT);
                    pgen.u16(ix);
                    genCount++;
                }
                for (Mod m : z.mods) {
                    writeMod(pmod, m);
                    modCount++;
                }
                bagCount++;
            }
        }
        checkU16(bagCount, "preset zones");
        checkU16(genCount, "preset generators");
        checkU16(modCount, "preset modulators");
        phdr.str("EOP", 20);
        phdr.u16(0);
        phdr.u16(0);
        phdr.u16(bagCount);
        phdr.u32(0);
        phdr.u32(0);
        phdr.u32(0);
        pbag.u16(genCount);
        pbag.u16(modCount);
        pgen.u16(0);
        pgen.u16(0);
        pmod.zeros(10);

        // ---- instruments ----
        bagCount = 0;
        genCount = 0;
        modCount = 0;
        for (Instrument in : sf.instruments) {
            inst.str(in.name, 20);
            inst.u16(bagCount);
            for (Zone z : in.zones) {
                ibag.u16(genCount);
                ibag.u16(modCount);
                for (Gen g : orderedGens(z.gens)) {
                    igen.u16(g.oper);
                    igen.u16(g.amount);
                    genCount++;
                }
                if (z.sample != null) {
                    Integer ix = sIdx.get(z.sample);
                    if (ix == null) {
                        throw new IOException("Instrument '" + in.name + "' uses a sample that is not in the file.");
                    }
                    igen.u16(Gens.SAMPLE_ID);
                    igen.u16(ix);
                    genCount++;
                }
                for (Mod m : z.mods) {
                    writeMod(imod, m);
                    modCount++;
                }
                bagCount++;
            }
        }
        checkU16(bagCount, "instrument zones");
        checkU16(genCount, "instrument generators");
        checkU16(modCount, "instrument modulators");
        inst.str("EOI", 20);
        inst.u16(bagCount);
        ibag.u16(genCount);
        ibag.u16(modCount);
        igen.u16(0);
        igen.u16(0);
        imod.zeros(10);

        // ---- sample headers ----
        long off = 0;
        boolean any24 = false;
        for (Sample s : sf.samples) {
            int len = s.length();
            if (s.data.has24()) any24 = true;
            long start = off;
            long endp = start + len;
            long sl = start + clamp(s.loopStart, 0, len);
            long el = start + clamp(s.loopEnd, 0, len);
            int type = s.sampleType & 0xF;
            int link = 0;
            if (s.link != null && sIdx.containsKey(s.link) && (type & (2 | 4 | 8)) != 0) {
                link = sIdx.get(s.link);
            } else {
                type = 1;
            }
            if (type == 0) type = 1;
            shdr.str(s.name, 20);
            shdr.u32(start);
            shdr.u32(endp);
            shdr.u32(sl);
            shdr.u32(el);
            shdr.u32(s.sampleRate);
            shdr.u8(s.originalPitch);
            shdr.u8(s.pitchCorrection);
            shdr.u16(link);
            shdr.u16(type);
            off += len + PAD;
        }
        shdr.str("EOS", 20);
        shdr.zeros(26);

        long totalPoints = off;
        long smplBytes = totalPoints * 2;
        long sm24Size = any24 ? totalPoints + (totalPoints & 1) : 0;

        byte[] info = buildInfo(sf);
        long infoList = 4 + info.length;
        long sdtaList = 4 + 8 + smplBytes + (any24 ? 8 + sm24Size : 0);
        long pdtaList = 4 + 9 * 8L + phdr.size() + pbag.size() + pmod.size() + pgen.size()
                + inst.size() + ibag.size() + imod.size() + igen.size() + shdr.size();
        long riff = 4 + (8 + infoList) + (8 + sdtaList) + (8 + pdtaList);
        if (riff > 0xFFFFFFFFL) throw new IOException("The result would be larger than the 4 GB SF2 limit.");

        Le head = new Le();
        head.tag("RIFF");
        head.u32(riff);
        head.tag("sfbk");
        head.tag("LIST");
        head.u32(infoList);
        head.tag("INFO");
        head.raw(info);
        head.tag("LIST");
        head.u32(sdtaList);
        head.tag("sdta");
        head.tag("smpl");
        head.u32(smplBytes);
        head.writeTo(out);

        double total = Math.max(1, any24 ? totalPoints * 2.0 : totalPoints);
        double done = 0;
        byte[] zeros16 = new byte[PAD * 2];
        for (Sample s : sf.samples) {
            s.data.write16(out);
            out.write(zeros16);
            done += s.length() + PAD;
            if (progress != null) progress.update(done / total);
        }

        Le mid = new Le();
        if (any24) {
            mid.tag("sm24");
            mid.u32(sm24Size);
            mid.writeTo(out);
            byte[] zeros24 = new byte[PAD];
            for (Sample s : sf.samples) {
                s.data.write24(out);
                out.write(zeros24);
                done += s.length() + PAD;
                if (progress != null) progress.update(done / total);
            }
            if ((totalPoints & 1) != 0) out.write(0);
        }

        Le tail = new Le();
        tail.tag("LIST");
        tail.u32(pdtaList);
        tail.tag("pdta");
        chunk(tail, "phdr", phdr);
        chunk(tail, "pbag", pbag);
        chunk(tail, "pmod", pmod);
        chunk(tail, "pgen", pgen);
        chunk(tail, "inst", inst);
        chunk(tail, "ibag", ibag);
        chunk(tail, "imod", imod);
        chunk(tail, "igen", igen);
        chunk(tail, "shdr", shdr);
        tail.writeTo(out);
        out.flush();
        if (progress != null) progress.update(1.0);
    }

    private static void chunk(Le dst, String id, Le body) {
        dst.tag(id);
        dst.u32(body.size());
        dst.raw(body.bytes());
    }

    private static void writeMod(Le b, Mod m) {
        b.u16(m.src);
        b.u16(m.dest);
        b.u16(m.amount);
        b.u16(m.amtSrc);
        b.u16(m.trans);
    }

    /** Key range first, velocity range second, others after; duplicates collapse to the last value. */
    private static List<Gen> orderedGens(List<Gen> in) {
        Map<Integer, Gen> byOper = new LinkedHashMap<>();
        for (Gen g : in) {
            if (g.oper == Gens.INSTRUMENT || g.oper == Gens.SAMPLE_ID) continue;
            byOper.remove(g.oper);
            byOper.put(g.oper, g);
        }
        List<Gen> out = new ArrayList<>();
        if (byOper.containsKey(Gens.KEY_RANGE)) out.add(byOper.get(Gens.KEY_RANGE));
        if (byOper.containsKey(Gens.VEL_RANGE)) out.add(byOper.get(Gens.VEL_RANGE));
        for (Gen g : byOper.values()) {
            if (g.oper != Gens.KEY_RANGE && g.oper != Gens.VEL_RANGE) out.add(g);
        }
        return out;
    }

    private static byte[] buildInfo(SoundFont sf) {
        Le b = new Le();
        b.tag("ifil");
        b.u32(4);
        b.u16(2);
        b.u16(4);
        strChunk(b, "isng", sf.engine == null || sf.engine.isEmpty() ? "EMU8000" : sf.engine, 255);
        strChunk(b, "INAM", sf.name == null || sf.name.isEmpty() ? "Untitled" : sf.name, 255);
        for (Map.Entry<String, String> e : sf.info.entrySet()) {
            String k = e.getKey();
            if (k.equals("ISFT")) continue;
            strChunk(b, k, e.getValue(), k.equals("ICMT") ? 65535 : 255);
        }
        strChunk(b, "ISFT", "SF2 Studio", 255);
        return b.bytes();
    }

    private static void strChunk(Le b, String id, String value, int maxLen) {
        byte[] raw = value.getBytes(StandardCharsets.ISO_8859_1);
        int len = Math.min(raw.length, maxLen);
        int padded = len + 1;
        if ((padded & 1) != 0) padded++;
        b.tag(id);
        b.u32(padded);
        b.raw(raw, len);
        b.zeros(padded - len);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void checkU16(int v, String what) throws IOException {
        if (v > 0xFFFF) throw new IOException("Too many " + what + " for the SF2 format (" + v + ").");
    }

    /** Tiny little-endian byte builder. */
    private static final class Le {
        private final ByteArrayOutputStream bo = new ByteArrayOutputStream();

        void u8(int v) {
            bo.write(v & 0xFF);
        }

        void u16(int v) {
            bo.write(v & 0xFF);
            bo.write((v >> 8) & 0xFF);
        }

        void u32(long v) {
            bo.write((int) (v & 0xFF));
            bo.write((int) ((v >> 8) & 0xFF));
            bo.write((int) ((v >> 16) & 0xFF));
            bo.write((int) ((v >> 24) & 0xFF));
        }

        void tag(String t) {
            byte[] b = t.getBytes(StandardCharsets.ISO_8859_1);
            bo.write(b, 0, 4);
        }

        void str(String s, int n) {
            byte[] raw = s == null ? new byte[0] : s.getBytes(StandardCharsets.ISO_8859_1);
            int c = Math.min(raw.length, n);
            bo.write(raw, 0, c);
            zeros(n - c);
        }

        void zeros(int n) {
            for (int i = 0; i < n; i++) bo.write(0);
        }

        void raw(byte[] b) {
            bo.write(b, 0, b.length);
        }

        void raw(byte[] b, int len) {
            bo.write(b, 0, len);
        }

        int size() {
            return bo.size();
        }

        byte[] bytes() {
            return bo.toByteArray();
        }

        void writeTo(OutputStream out) throws IOException {
            bo.writeTo(out);
        }
    }
}
