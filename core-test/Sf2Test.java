import com.sf2studio.sf2.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

/**
 * JVM test for the SF2 engine.
 * args: realSf2 outDir [wavWithLoop]
 */
public class Sf2Test {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + msg);
        if (!ok) failures++;
    }

    static SoundFont load(String path) throws Exception {
        FileChannel ch = FileChannel.open(Paths.get(path), StandardOpenOption.READ);
        return Sf2Reader.read(new ChannelSource(ch));
    }

    static void save(SoundFont sf, String path) throws Exception {
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(path), 1 << 16)) {
            Sf2Writer.write(sf, o, null);
        }
    }

    static String md5(SampleData d) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("MD5");
        d.write16(new OutputStream() {
            public void write(int b) { md.update((byte) b); }
            public void write(byte[] b, int o, int l) { md.update(b, o, l); }
        });
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String zoneKey(Zone z) {
        List<String> g = new ArrayList<>();
        for (Gen x : z.gens) g.add(x.oper + "=" + x.amount);
        Collections.sort(g);
        List<String> m = new ArrayList<>();
        for (Mod x : z.mods) m.add(x.src + "," + x.dest + "," + x.amount + "," + x.amtSrc + "," + x.trans);
        String ref = z.instrument != null ? "I:" + z.instrument.name : z.sample != null ? "S:" + z.sample.name : "global";
        return ref + g + m;
    }

    static List<String> zoneKeys(List<Zone> zs) {
        List<String> l = new ArrayList<>();
        for (Zone z : zs) l.add(zoneKey(z));
        return l;
    }

    static void sameStructure(String label, SoundFont a, SoundFont b) throws Exception {
        check(a.presets.size() == b.presets.size(), label + ": preset count " + a.presets.size());
        check(a.instruments.size() == b.instruments.size(), label + ": instrument count " + a.instruments.size());
        check(a.samples.size() == b.samples.size(), label + ": sample count " + a.samples.size());
        check(a.name.equals(b.name), label + ": name kept '" + a.name + "'");

        boolean samplesOk = a.samples.size() == b.samples.size();
        for (int i = 0; samplesOk && i < a.samples.size(); i++) {
            Sample x = a.samples.get(i), y = b.samples.get(i);
            if (!x.name.equals(y.name) || x.sampleRate != y.sampleRate || x.originalPitch != y.originalPitch
                    || x.pitchCorrection != y.pitchCorrection || x.loopStart != y.loopStart
                    || x.loopEnd != y.loopEnd || x.length() != y.length()
                    || x.sampleType != y.sampleType || !md5(x.data).equals(md5(y.data))) {
                samplesOk = false;
                System.out.println("   sample mismatch at " + i + " " + x.name);
            }
        }
        check(samplesOk, label + ": every sample header + PCM identical");

        boolean instOk = a.instruments.size() == b.instruments.size();
        for (int i = 0; instOk && i < a.instruments.size(); i++) {
            Instrument x = a.instruments.get(i), y = b.instruments.get(i);
            if (!x.name.equals(y.name) || !zoneKeys(x.zones).equals(zoneKeys(y.zones))) {
                instOk = false;
                System.out.println("   instrument mismatch at " + i + " " + x.name);
            }
        }
        check(instOk, label + ": every instrument and zone identical");

        Map<String, Preset> pb = new HashMap<>();
        for (Preset p : b.presets) pb.put(p.bank + ":" + p.program, p);
        boolean presetOk = true;
        for (Preset p : a.presets) {
            Preset q = pb.get(p.bank + ":" + p.program);
            if (q == null || !q.name.equals(p.name) || !zoneKeys(p.zones).equals(zoneKeys(q.zones))) {
                presetOk = false;
                System.out.println("   preset mismatch " + p.bank + ":" + p.program + " " + p.name);
            }
        }
        check(presetOk, label + ": every preset (by bank/program) identical");
    }

    public static void main(String[] args) throws Exception {
        String real = args[0];
        String out = args[1];
        new File(out).mkdirs();

        // ---------- 1. real file round trip ----------
        SoundFont a = load(real);
        System.out.println("Loaded real: " + a.presets.size() + " presets, " + a.instruments.size()
                + " instruments, " + a.samples.size() + " samples, name '" + a.name + "'");
        save(a, out + "/roundtrip.sf2");
        SoundFont b = load(out + "/roundtrip.sf2");
        sameStructure("roundtrip", a, b);
        System.out.println("   sizes: original " + new File(real).length() + " -> rewritten "
                + new File(out + "/roundtrip.sf2").length());

        // ---------- 2. merge a file with itself ----------
        SoundFont m1 = load(real), m2 = load(real);
        int origPresets = m1.presets.size();
        int imported = Sf2Ops.importPresets(m1, m2, m2.presets, Sf2Ops.Conflict.RENUMBER);
        check(imported == origPresets, "merge: imported " + imported + " of " + m2.presets.size());
        check(m1.presets.size() == 2 * origPresets, "merge: preset count doubled to " + m1.presets.size());
        Set<String> slots = new HashSet<>();
        boolean unique = true;
        for (Preset p : m1.presets) if (!slots.add(p.bank + ":" + p.program)) unique = false;
        check(unique, "merge: all bank/program slots unique after renumbering");
        check(m1.samples.size() == 2 * m2.samples.size(), "merge: samples doubled to " + m1.samples.size());
        // Doubling this particular file exceeds the 16-bit generator index of the SF2 format.
        String limit = Sf2Writer.limitProblem(m1);
        check(limit != null, "merge: limit check flags the doubled file -> " + limit);
        try {
            save(m1, out + "/too-big.sf2");
            check(false, "merge: writer refuses an over-limit soundfont");
        } catch (IOException e) {
            check(e.getMessage().startsWith("Too many"), "merge: writer refuses an over-limit soundfont cleanly");
        }

        // A merge that fits: original + 20 presets of a second copy.
        SoundFont f1 = load(real), f2 = load(real);
        int baseSamples = f1.samples.size();
        int got = Sf2Ops.importPresets(f1, f2, new ArrayList<>(f2.presets.subList(0, 20)), Sf2Ops.Conflict.RENUMBER);
        check(got == 20 && Sf2Writer.limitProblem(f1) == null, "merge-fit: 20 presets imported, within format limits");
        save(f1, out + "/merged.sf2");
        SoundFont m3 = load(out + "/merged.sf2");
        sameStructure("merged-reload", f1, m3);
        System.out.println("   merged-fit samples: " + baseSamples + " -> " + f1.samples.size());

        // ---------- 3. import a subset, policies ----------
        SoundFont src = load(real);
        Preset piano = src.findPreset(0, 0);
        check(piano != null, "subset: found preset 000:000 '" + (piano == null ? "?" : piano.name) + "'");
        SoundFont fresh = new SoundFont();
        fresh.name = "Subset Test";
        List<Preset> pick = new ArrayList<>();
        pick.add(piano);
        int n = Sf2Ops.importPresets(fresh, src, pick, Sf2Ops.Conflict.SKIP);
        check(n == 1 && fresh.presets.size() == 1, "subset: imported 1 preset");
        check(fresh.samples.size() < src.samples.size() && fresh.samples.size() > 0,
                "subset: only needed samples copied (" + fresh.samples.size() + " of " + src.samples.size() + ")");
        int[] removed = Sf2Ops.removeUnused(fresh);
        check(removed[0] == 0 && removed[1] == 0, "subset: nothing unused after import");
        check(Sf2Ops.importPresets(fresh, src, pick, Sf2Ops.Conflict.SKIP) == 0, "policy SKIP skips a clash");
        check(Sf2Ops.importPresets(fresh, src, pick, Sf2Ops.Conflict.KEEP_BOTH) == 1, "policy KEEP_BOTH keeps a duplicate");
        check(fresh.presets.size() == 2 && fresh.instruments.size() == 1 && fresh.samples.size() == 9,
                "re-import from the same file shares instruments/samples (no duplicated PCM)");
        Sf2Ops.deletePreset(fresh, fresh.presets.get(1));
        int[] r2 = Sf2Ops.removeUnused(fresh);
        check(r2[0] == 0 && r2[1] == 0, "cleanup after deleting the duplicate preset removes nothing shared");

        // importing a second preset that shares data with the first must not copy shared samples twice
        SoundFont shared = new SoundFont();
        List<Preset> two = new ArrayList<>(src.presets.subList(0, 2));
        Sf2Ops.importPresets(shared, src, two.subList(0, 1), Sf2Ops.Conflict.RENUMBER);
        int afterFirst = shared.samples.size();
        Sf2Ops.importPresets(shared, src, two.subList(1, 2), Sf2Ops.Conflict.RENUMBER);
        SoundFont both = new SoundFont();
        Sf2Ops.importPresets(both, src, two, Sf2Ops.Conflict.RENUMBER);
        check(shared.samples.size() == both.samples.size() && shared.instruments.size() == both.instruments.size(),
                "importing presets one at a time ends with the same sample/instrument counts as all at once ("
                        + afterFirst + " -> " + shared.samples.size() + ")");
        save(fresh, out + "/subset.sf2");
        SoundFont fr = load(out + "/subset.sf2");
        sameStructure("subset-reload", fresh, fr);
        Map<String, String> origMd5 = new HashMap<>();
        for (Sample s : src.samples) origMd5.put(s.name, md5(s.data));
        boolean pcmSame = true;
        for (Sample s : fr.samples) if (!md5(s.data).equals(origMd5.get(s.name))) pcmSame = false;
        check(pcmSame, "subset: PCM identical to the original file's samples");

        // ---------- 4. delete operations ----------
        SoundFont d = load(real);
        Sample victim = d.samples.get(5);
        int usesBefore = Sf2Ops.countUses(d, victim);
        Sf2Ops.deleteSample(d, victim);
        boolean dangling = false;
        for (Instrument in : d.instruments) for (Zone z : in.zones) if (z.sample == victim) dangling = true;
        check(!dangling && !d.samples.contains(victim), "delete sample '" + victim.name + "' (" + usesBefore + " uses) leaves no references");
        Instrument vi = d.instruments.get(3);
        Sf2Ops.deleteInstrument(d, vi);
        boolean danglingI = false;
        for (Preset p : d.presets) for (Zone z : p.zones) if (z.instrument == vi) danglingI = true;
        check(!danglingI, "delete instrument '" + vi.name + "' leaves no references");
        save(d, out + "/deleted.sf2");
        SoundFont d2 = load(out + "/deleted.sf2");
        sameStructure("deleted-reload", d, d2);

        // ---------- 5. WAV import ----------
        if (args.length > 2) {
            WavImport.Wav w = WavImport.read(new FileInputStream(args[2]));
            check(w.pcm.length == 22050 && w.rate == 22050, "wav: 22050 frames @ 22050 Hz (got " + w.pcm.length + " @ " + w.rate + ")");
            check(w.rootKey == 69, "wav: root key from smpl chunk = " + w.rootKey);
            check(w.loopStart == 1000 && w.loopEnd == 20001, "wav: loop " + w.loopStart + ".." + w.loopEnd);
            SoundFont nw = new SoundFont();
            nw.name = "WAV Test";
            Preset np = Sf2Ops.addSampleAsPreset(nw, "Sine A4", w.pcm, w.rate, w.rootKey, w.loopStart, w.loopEnd);
            check(np.bank == 0 && np.program == 0, "wav: preset placed at 000:000");
            save(nw, out + "/wav.sf2");
            SoundFont nr = load(out + "/wav.sf2");
            Sample s = nr.samples.get(0);
            short[] back = s.data.readShorts(0, s.length());
            check(Arrays.equals(back, w.pcm), "wav: PCM identical after save/load");
            check(s.originalPitch == 69 && s.sampleRate == 22050 && s.loopStart == 1000 && s.loopEnd == 20001,
                    "wav: root/rate/loop kept in the sf2");
            Zone iz = nr.instruments.get(0).zones.get(0);
            check(iz.find(Gens.SAMPLE_MODES) != null && iz.find(Gens.SAMPLE_MODES).amount == 1, "wav: loop-on generator written");
        }

        // ---------- 6. 24-bit data and stereo links ----------
        SoundFont s24 = new SoundFont();
        s24.name = "24bit";
        short[] pa = new short[100], pb2 = new short[77];
        byte[] low = new byte[100];
        for (int i = 0; i < pa.length; i++) { pa[i] = (short) (i * 300 - 15000); low[i] = (byte) (i * 7); }
        for (int i = 0; i < pb2.length; i++) pb2[i] = (short) (1000 - i * 20);
        Sample sa = new Sample(); sa.name = "L"; sa.data = new MemSampleData(pa, low); sa.sampleType = 4;
        Sample sb = new Sample(); sb.name = "R"; sb.data = new MemSampleData(pb2, null); sb.sampleType = 2;
        sa.link = sb; sb.link = sa;
        s24.samples.add(sa); s24.samples.add(sb);
        Instrument ins = new Instrument(); ins.name = "stereo";
        Zone zl = new Zone(); zl.sample = sa; zl.gens.add(Gen.range(Gens.KEY_RANGE, 0, 127)); zl.gens.add(new Gen(Gens.PAN, (short) -500));
        Zone zr = new Zone(); zr.sample = sb; zr.gens.add(new Gen(Gens.PAN, 500)); zr.gens.add(Gen.range(Gens.VEL_RANGE, 1, 127)); zr.gens.add(Gen.range(Gens.KEY_RANGE, 0, 127));
        ins.zones.add(zl); ins.zones.add(zr);
        s24.instruments.add(ins);
        Preset sp = new Preset(); sp.name = "Stereo"; Zone pz = new Zone(); pz.instrument = ins; sp.zones.add(pz);
        s24.presets.add(sp);
        save(s24, out + "/s24.sf2");
        SoundFont r24 = load(out + "/s24.sf2");
        Sample ra = r24.samples.get(0), rb = r24.samples.get(1);
        check(Arrays.equals(ra.data.readShorts(0, 100), pa) && Arrays.equals(rb.data.readShorts(0, 77), pb2), "24bit: 16-bit parts identical");
        check(ra.data.has24() && rb.data.has24(), "24bit: sm24 chunk present for the whole file");
        ByteArrayOutputStream lb = new ByteArrayOutputStream();
        ra.data.write24(lb);
        check(Arrays.equals(lb.toByteArray(), low), "24bit: low bytes identical");
        ByteArrayOutputStream zb = new ByteArrayOutputStream();
        rb.data.write24(zb);
        boolean allZero = true;
        for (byte x : zb.toByteArray()) if (x != 0) allZero = false;
        check(allZero && zb.size() == 77, "24bit: sample without low bytes reads as zeros");
        check(ra.link == rb && rb.link == ra && ra.sampleType == 4 && rb.sampleType == 2, "stereo: link + left/right types preserved");
        Zone first = r24.instruments.get(0).zones.get(1);
        check(first.gens.size() >= 3 && first.gens.get(0).oper == Gens.KEY_RANGE && first.gens.get(1).oper == Gens.VEL_RANGE,
                "generators written key range, velocity range first");
        check(first.find(Gens.PAN).signed() == 500, "generator values kept");

        // ---------- 7. slot allocation ----------
        SoundFont slot = new SoundFont();
        for (int p = 0; p < 128; p++) { Preset x = new Preset(); x.bank = 0; x.program = p; slot.presets.add(x); }
        int[] free = Sf2Ops.nextFreeSlot(slot, 0, 5);
        check(free != null && free[0] == 1 && free[1] == 0, "slots: full bank 0 overflows to bank 1 prog 0");

        // ---------- 8. error handling ----------
        try {
            File junk = new File(out + "/junk.sf2");
            try (FileOutputStream fo = new FileOutputStream(junk)) { fo.write("this is not a soundfont at all".getBytes()); }
            load(junk.getPath());
            check(false, "junk file rejected");
        } catch (IOException e) {
            check(true, "junk file rejected: " + e.getMessage());
        }

        System.out.println();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : (failures + " TEST(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
