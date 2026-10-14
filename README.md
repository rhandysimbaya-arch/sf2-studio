# SF2 Studio

An Android app (Java, no third-party libraries) to open, edit, merge and save SoundFont 2 (`.sf2`) files.

## What it does

| Feature | Notes |
|---|---|
| **Open** a .sf2 | Presets / Instruments / Samples tabs. Sample audio is streamed from the file, not loaded into RAM, so large soundfonts work. |
| **Rename** | Presets, instruments, samples, and the soundfont itself (tap the title). |
| **Edit** | Preset bank/program; sample root key, fine tune, loop start/end, sample rate. |
| **Preview** | Plays a sample (first 15 s). |
| **Delete** | Preset / instrument / sample, with dependent zones removed. **Clean up** drops anything unused. |
| **Merge in** | Open a second .sf2, tick the presets you want, and they are copied in with only the instruments and samples they need. Bank/program clashes can be moved to free slots, skipped, or kept. |
| **Add WAV** | Imports a PCM/float WAV (stereo mixed to mono) as a new sample + instrument + preset. Reads root key and loop from the WAV `smpl` chunk. |
| **Save as** | "Compiles" everything into a fresh, valid SoundFont 2.04 file (sorted presets, correct zone/generator tables, 46-point sample padding, 24-bit `sm24` data kept). |

Not supported: SF3 (Ogg-compressed) soundfonts, editing individual zone generators (envelopes, filters), creating modulators. Modulators and generators in existing files are preserved untouched.

SF2 limit to know about: zone/generator indexes are 16-bit, so one soundfont can hold at most 65,535 instrument settings. Merging two very large soundfonts can exceed that; the app tells you right after the import and refuses to write a broken file.

## Getting the APK

You need an Android build environment. Two easy routes:

### A. GitHub (no installs)
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder and `gradlew`).
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. When it finishes, download the `SF2Studio-debug-apk` artifact, unzip it, and install `app-debug.apk` (allow "install unknown apps" for your browser/files app).

### B. Android Studio
Open this folder in Android Studio, let it sync, then **Build > Build APK(s)**. Or from a terminal with the Android SDK installed: `./gradlew assembleDebug` (APK in `app/build/outputs/apk/debug/`).

The APK is debug-signed, which is fine for installing on your own devices.

## Layout

```
app/src/main/java/com/sf2studio/MainActivity.java   UI
app/src/main/java/com/sf2studio/sf2/                SF2 engine (pure Java, no Android classes)
core-test/                                          JVM tests for the engine (see below)
```

## Engine tests

The engine in `com/sf2studio/sf2` has no Android dependencies, so it is tested on a plain JVM:

```
javac -d out app/src/main/java/com/sf2studio/sf2/*.java core-test/Sf2Test.java
python3 core-test/make_wav.py sine.wav
java -cp out Sf2Test some.sf2 outdir sine.wav
pip install sf2utils audioop-lts
python3 core-test/verify.py same some.sf2 outdir/roundtrip.sf2
```

Tested against a real 6 MB soundfont (136 presets, 210 instruments, 520 samples): rewriting reproduces every sample (byte-identical PCM), instrument and preset, as seen by both this engine and the independent `sf2utils` parser.

## Notes

- Saving always writes a **new file**; you can't overwrite a soundfont you currently have open (its samples are being read from it).
- The app keeps its working state in memory only. Save before leaving it for long.
- Files from some cloud providers aren't seekable; the app copies those to its cache first (needs free space about the size of the file).
