"""Independent check of files written by the Java engine, using the sf2utils parser.

usage: verify.py same <a.sf2> <b.sf2>     strict structural + PCM equality (preset order ignored)
       verify.py info <a.sf2>             counts only
       verify.py superset <orig> <merged> <n>  merged file keeps every original sample and has n more presets
"""
import hashlib
import logging
import sys
from collections import Counter

logging.disable(logging.CRITICAL)
from sf2utils.sf2parse import Sf2File  # noqa: E402


def load(path):
    # sf2utils reads sample data lazily, so the file must stay open
    return Sf2File(open(path, "rb"))


def md5(b):
    return hashlib.md5(b).hexdigest()


def gens(bag):
    return sorted((k, v.amount) for k, v in bag.gens.items())


def summary(sf):
    samples = [(s.name, s.sample_rate, s.original_pitch, s.pitch_correction, s.sample_type, md5(s.raw_sample_data))
               for s in sf.samples if s.name != "EOS"]
    insts = []
    for i in sf.instruments:
        if i.is_sentinel():
            continue
        insts.append((i.name, [gens(b) for b in i.bags]))
    presets = {}
    for p in sf.presets:
        if p.name == "EOP":
            continue
        presets[(p.bank, p.preset)] = (p.name, [gens(b) for b in p.bags])
    return samples, insts, presets


def main():
    mode = sys.argv[1]
    if mode == "info":
        s, i, p = summary(load(sys.argv[2]))
        print(f"{sys.argv[2]}: {len(p)} presets, {len(i)} instruments, {len(s)} samples")
        return 0
    if mode == "same":
        a, b = summary(load(sys.argv[2])), summary(load(sys.argv[3]))
        ok = True
        for label, x, y in zip(("samples", "instruments", "presets"), a, b):
            same = x == y
            ok &= same
            print(("PASS " if same else "FAIL ") + f"sf2utils sees identical {label} ({len(x)})")
        return 0 if ok else 1
    if mode == "superset":
        a, b = summary(load(sys.argv[2])), summary(load(sys.argv[3]))
        base = Counter(s[5] for s in a[0])
        merged = Counter(s[5] for s in b[0])
        ok = all(merged[k] >= v for k, v in base.items())
        print(("PASS " if ok else "FAIL ") + f"sf2utils: every original sample is still in the merged file ({sum(merged.values())} samples)")
        pok = len(b[2]) == len(a[2]) + int(sys.argv[4])
        print(("PASS " if pok else "FAIL ") + f"sf2utils: preset count {len(a[2])} -> {len(b[2])} (expected +{sys.argv[4]})")
        return 0 if ok and pok else 1
    return 2


sys.exit(main())
