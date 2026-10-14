package com.sf2studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sf2studio.sf2.ChannelSource;
import com.sf2studio.sf2.Instrument;
import com.sf2studio.sf2.Preset;
import com.sf2studio.sf2.Sample;
import com.sf2studio.sf2.Sf2Ops;
import com.sf2studio.sf2.Sf2Reader;
import com.sf2studio.sf2.Sf2Writer;
import com.sf2studio.sf2.SoundFont;
import com.sf2studio.sf2.Source;
import com.sf2studio.sf2.WavImport;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SF2 Studio: open, edit, merge and save (compile) SoundFont 2 files.
 * Sample audio is streamed from the source files, so large soundfonts do not have to fit in memory.
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1;
    private static final int REQ_MERGE = 2;
    private static final int REQ_WAV = 3;
    private static final int REQ_SAVE = 4;

    private interface Work {
        void run(Sf2Writer.Progress progress) throws Exception;
    }

    private interface TextResult {
        void ok(String text);
    }

    /** Files and descriptors a loaded soundfont streams its samples from. */
    private static final class Session {
        final List<Closeable> res = new ArrayList<>();
        final List<File> temps = new ArrayList<>();
        final List<String> uris = new ArrayList<>();

        void absorb(Session o) {
            res.addAll(o.res);
            temps.addAll(o.temps);
            uris.addAll(o.uris);
            o.res.clear();
            o.temps.clear();
            o.uris.clear();
        }

        void close() {
            for (Closeable c : res) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
            res.clear();
            for (File f : temps) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            temps.clear();
        }
    }

    private SoundFont sf = new SoundFont();
    private Session session = new Session();
    private boolean dirty = false;
    private int tab = 0;
    private List<Preset> viewPresets = new ArrayList<>();

    private TextView status;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private final Button[] tabButtons = new Button[3];
    private static final String[] TAB_NAMES = {"Presets", "Instruments", "Samples"};

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioTrack track;

    // ------------------------------------------------------------------ lifecycle / UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreview();
        exec.shutdownNow();
        session.close();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 0, 0, dp(6));
        status.setOnClickListener(v -> renameSoundFont());
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(row(
                button("Open", v -> confirmDiscard(() -> pick(REQ_OPEN))),
                button("New", v -> confirmDiscard(this::newSoundFont)),
                button("Save as", v -> startSave())));
        root.addView(row(
                button("Merge in", v -> pick(REQ_MERGE)),
                button("Add WAV", v -> pick(REQ_WAV)),
                button("Clean up", v -> cleanUp())));

        Button[] tabs = new Button[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tabs[i] = button(TAB_NAMES[i], v -> {
                tab = index;
                refresh();
            });
            tabButtons[i] = tabs[i];
        }
        root.addView(row(tabs));

        listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRowClick(position);
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void refresh() {
        rows.clear();
        if (tab == 0) {
            viewPresets = Sf2Ops.sortedPresets(sf);
            for (Preset p : viewPresets) {
                rows.add(String.format(Locale.US, "%03d:%03d   %s   (%d zone%s)",
                        p.bank, p.program, p.name, p.zones.size(), p.zones.size() == 1 ? "" : "s"));
            }
        } else if (tab == 1) {
            for (Instrument in : sf.instruments) {
                rows.add(String.format(Locale.US, "%s   (%d zone%s)",
                        in.name, in.zones.size(), in.zones.size() == 1 ? "" : "s"));
            }
        } else {
            for (Sample s : sf.samples) {
                double secs = s.sampleRate > 0 ? s.length() / (double) s.sampleRate : 0;
                rows.add(String.format(Locale.US, "%s   (%d Hz, %.2f s, root %d)",
                        s.name, s.sampleRate, secs, s.originalPitch));
            }
        }
        adapter.notifyDataSetChanged();
        for (int i = 0; i < 3; i++) {
            tabButtons[i].setText((i == tab ? "● " : "") + TAB_NAMES[i]);
        }
        String name = sf.name == null || sf.name.isEmpty() ? "(no name)" : sf.name;
        status.setText(name + (dirty ? "   • unsaved" : "") + "\n"
                + sf.presets.size() + " presets, " + sf.instruments.size() + " instruments, "
                + sf.samples.size() + " samples   (tap here to rename)");
    }

    private void onRowClick(int pos) {
        if (tab == 0) {
            if (pos < viewPresets.size()) presetDialog(viewPresets.get(pos));
        } else if (tab == 1) {
            if (pos < sf.instruments.size()) instrumentDialog(sf.instruments.get(pos));
        } else {
            if (pos < sf.samples.size()) sampleDialog(sf.samples.get(pos));
        }
    }

    private void changed() {
        dirty = true;
        refresh();
    }

    // ------------------------------------------------------------------ item dialogs

    private void presetDialog(final Preset p) {
        String title = String.format(Locale.US, "%s  (bank %d, program %d)", p.name, p.bank, p.program);
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(new String[]{"Rename", "Change bank / program", "Delete"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    promptText("Rename preset", p.name, 19, text -> {
                                        p.name = Sf2Ops.clampName(text);
                                        changed();
                                    });
                                } else if (which == 1) {
                                    editSlot(p);
                                } else {
                                    confirm("Delete preset \"" + p.name + "\"?", () -> {
                                        Sf2Ops.deletePreset(sf, p);
                                        changed();
                                    });
                                }
                            }
                        })
                .show();
    }

    private void editSlot(final Preset p) {
        final LinearLayout box = form();
        final EditText bank = field(box, "Bank (0 - 16383)", String.valueOf(p.bank), false);
        final EditText prog = field(box, "Program (0 - 127)", String.valueOf(p.program), false);
        new AlertDialog.Builder(this).setTitle("Bank / program").setView(scroll(box))
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int b = Integer.parseInt(bank.getText().toString().trim());
                        int g = Integer.parseInt(prog.getText().toString().trim());
                        if (b < 0 || b > 16383 || g < 0 || g > 127) throw new NumberFormatException();
                        Preset other = sf.findPreset(b, g);
                        if (other != null && other != p) {
                            toast("Slot " + b + ":" + g + " is already used by \"" + other.name + "\"");
                            return;
                        }
                        p.bank = b;
                        p.program = g;
                        changed();
                    } catch (NumberFormatException e) {
                        toast("Please enter valid numbers.");
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void instrumentDialog(final Instrument in) {
        final int uses = Sf2Ops.countUses(sf, in);
        String title = String.format(Locale.US, "%s  (%d zones, used by %d preset zone%s)",
                in.name, in.zones.size(), uses, uses == 1 ? "" : "s");
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(new String[]{"Rename", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        promptText("Rename instrument", in.name, 19, text -> {
                            in.name = Sf2Ops.clampName(text);
                            changed();
                        });
                    } else {
                        String msg = "Delete instrument \"" + in.name + "\"?"
                                + (uses > 0 ? "\n\nThe " + uses + " preset zone(s) that use it will be removed too." : "");
                        confirm(msg, () -> {
                            Sf2Ops.deleteInstrument(sf, in);
                            changed();
                        });
                    }
                }).show();
    }

    private void sampleDialog(final Sample s) {
        String title = String.format(Locale.US, "%s  (%d Hz, %d points)", s.name, s.sampleRate, s.length());
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(new String[]{"Preview", "Rename", "Edit pitch / loop / rate", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        previewSample(s);
                    } else if (which == 1) {
                        promptText("Rename sample", s.name, 19, text -> {
                            s.name = Sf2Ops.clampName(text);
                            changed();
                        });
                    } else if (which == 2) {
                        editSample(s);
                    } else {
                        int uses = Sf2Ops.countUses(sf, s);
                        String msg = "Delete sample \"" + s.name + "\"?"
                                + (uses > 0 ? "\n\nThe " + uses + " instrument zone(s) that use it will be removed too." : "");
                        confirm(msg, () -> {
                            Sf2Ops.deleteSample(sf, s);
                            changed();
                        });
                    }
                }).show();
    }

    private void editSample(final Sample s) {
        final LinearLayout box = form();
        final EditText root = field(box, "Root key (MIDI note 0 - 127, 255 = unpitched)", String.valueOf(s.originalPitch), false);
        final EditText tune = field(box, "Fine tune in cents (-99 to 99)", String.valueOf(s.pitchCorrection), true);
        final EditText ls = field(box, "Loop start (sample point)", String.valueOf(s.loopStart), false);
        final EditText le = field(box, "Loop end (sample point, max " + s.length() + ")", String.valueOf(s.loopEnd), false);
        final EditText rate = field(box, "Sample rate (Hz)", String.valueOf(s.sampleRate), false);
        new AlertDialog.Builder(this).setTitle("Edit \"" + s.name + "\"").setView(scroll(box))
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int r = Integer.parseInt(root.getText().toString().trim());
                        int t = Integer.parseInt(tune.getText().toString().trim());
                        int a = Integer.parseInt(ls.getText().toString().trim());
                        int b = Integer.parseInt(le.getText().toString().trim());
                        int hz = Integer.parseInt(rate.getText().toString().trim());
                        boolean ok = (r >= 0 && r <= 127 || r == 255) && t >= -99 && t <= 99
                                && a >= 0 && a <= b && b <= s.length() && hz >= 400 && hz <= 96000;
                        if (!ok) {
                            toast("A value is out of range - nothing changed.");
                            return;
                        }
                        s.originalPitch = r;
                        s.pitchCorrection = t;
                        s.loopStart = a;
                        s.loopEnd = b;
                        s.sampleRate = hz;
                        changed();
                    } catch (NumberFormatException e) {
                        toast("Please enter valid numbers - nothing changed.");
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ preview

    private void previewSample(final Sample s) {
        stopPreview();
        exec.execute(() -> {
            try {
                int n = (int) Math.min((long) s.length(), Math.max(1L, s.sampleRate) * 15L);
                if (n <= 0) {
                    ui.post(() -> toast("This sample is empty."));
                    return;
                }
                final short[] pcm = s.data.readShorts(0, n);
                final int rate = Math.max(4000, Math.min(192000, s.sampleRate));
                ui.post(() -> play(pcm, rate));
            } catch (final Throwable t) {
                ui.post(() -> showError(t));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void play(short[] pcm, int rate) {
        stopPreview();
        try {
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
            t.write(pcm, 0, pcm.length);
            t.play();
            track = t;
        } catch (Exception e) {
            showError(e);
        }
    }

    private void stopPreview() {
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    // ------------------------------------------------------------------ file picking

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (requestCode) {
            case REQ_OPEN:
                openFile(uri);
                break;
            case REQ_MERGE:
                openMergeFile(uri);
                break;
            case REQ_WAV:
                importWav(uri);
                break;
            case REQ_SAVE:
                saveTo(uri);
                break;
            default:
                break;
        }
    }

    /** Opens the file as a seekable source; copies it to the cache when the provider is not seekable. */
    private Source openSource(Uri uri, Session s) throws IOException {
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Could not open the file.");
        s.res.add(pfd);
        if (pfd.getStatSize() >= 0) {
            try {
                FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                s.res.add(fis);
                ChannelSource cs = new ChannelSource(fis.getChannel());
                if (cs.length() > 0) cs.readFully(0, new byte[1], 0, 1);
                return cs;
            } catch (IOException notSeekable) {
                // fall through to the copy below
            }
        }
        File f = File.createTempFile("sf2_", ".tmp", getCacheDir());
        s.temps.add(f);
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Could not read the file.");
        try (OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        s.res.add(raf);
        FileChannel ch = raf.getChannel();
        return new ChannelSource(ch);
    }

    private void openFile(final Uri uri) {
        final Session ns = new Session();
        final SoundFont[] box = new SoundFont[1];
        runWithProgress("Opening soundfont...", p -> {
            Source src = openSource(uri, ns);
            box[0] = Sf2Reader.read(src);
            ns.uris.add(uri.toString());
        }, () -> {
            session.close();
            session = ns;
            sf = box[0];
            dirty = false;
            refresh();
        }, ns::close);
    }

    private void newSoundFont() {
        session.close();
        session = new Session();
        sf = new SoundFont();
        dirty = false;
        refresh();
    }

    // ------------------------------------------------------------------ merge

    private void openMergeFile(final Uri uri) {
        final Session ns = new Session();
        final SoundFont[] box = new SoundFont[1];
        runWithProgress("Reading soundfont to merge...", p -> {
            Source src = openSource(uri, ns);
            box[0] = Sf2Reader.read(src);
            ns.uris.add(uri.toString());
        }, () -> showMergeDialog(box[0], ns), ns::close);
    }

    private void showMergeDialog(final SoundFont other, final Session ns) {
        final List<Preset> list = Sf2Ops.sortedPresets(other);
        if (list.isEmpty()) {
            ns.close();
            toast("That soundfont has no presets.");
            return;
        }
        String[] labels = new String[list.size()];
        final boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < labels.length; i++) {
            Preset p = list.get(i);
            labels[i] = String.format(Locale.US, "%03d:%03d  %s", p.bank, p.program, p.name);
            checked[i] = true;
        }
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Import from \"" + other.name + "\"")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Import", (d, w) -> {
                    List<Preset> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(list.get(i));
                    askConflictPolicy(other, ns, chosen);
                })
                .setNegativeButton("Cancel", (d, w) -> ns.close())
                .setNeutralButton("All / none", null)
                .setOnCancelListener(d -> ns.close())
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean any = false;
            for (boolean c : checked) any |= c;
            boolean target = !any;
            ListView lv = dlg.getListView();
            for (int i = 0; i < checked.length; i++) {
                checked[i] = target;
                lv.setItemChecked(i, target);
            }
        });
    }

    private void askConflictPolicy(final SoundFont other, final Session ns, final List<Preset> chosen) {
        if (chosen.isEmpty()) {
            ns.close();
            toast("Nothing selected.");
            return;
        }
        String[] opts = {
                "Move clashing presets to free slots (recommended)",
                "Skip presets whose bank/program is taken",
                "Keep both (duplicate bank/program)"};
        new AlertDialog.Builder(this).setTitle("If a bank/program is already used...")
                .setItems(opts, (d, which) -> {
                    Sf2Ops.Conflict c = which == 0 ? Sf2Ops.Conflict.RENUMBER
                            : which == 1 ? Sf2Ops.Conflict.SKIP : Sf2Ops.Conflict.KEEP_BOTH;
                    int n = Sf2Ops.importPresets(sf, other, chosen, c);
                    if (n == 0) {
                        ns.close();
                        toast("No presets were imported.");
                        return;
                    }
                    session.absorb(ns);
                    changed();
                    String prob = Sf2Writer.limitProblem(sf);
                    if (prob != null) showMessage("Imported, but too big to save", prob);
                    else toast(n + " preset(s) imported.");
                })
                .setOnCancelListener(d -> ns.close())
                .show();
    }

    // ------------------------------------------------------------------ WAV import

    private void importWav(final Uri uri) {
        final WavImport.Wav[] box = new WavImport.Wav[1];
        final String base = baseName(displayName(uri));
        runWithProgress("Reading WAV...", p -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open the WAV file.");
                box[0] = WavImport.read(in);
            }
        }, () -> showWavDialog(box[0], base), null);
    }

    private void showWavDialog(final WavImport.Wav w, String base) {
        final LinearLayout box = form();
        TextView info = new TextView(this);
        info.setText(String.format(Locale.US, "%d points at %d Hz (%.2f s)%s. Stereo files are mixed to mono.",
                w.pcm.length, w.rate, w.pcm.length / (double) w.rate,
                w.loopStart >= 0 ? ", loop found" : ""));
        box.addView(info);
        final EditText name = field(box, "Name (max 19 characters)", Sf2Ops.clampName(base), false);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(19)});
        final EditText root = field(box, "Root key (MIDI note, 60 = middle C)", String.valueOf(w.rootKey), false);
        new AlertDialog.Builder(this).setTitle("Add WAV as a new preset").setView(scroll(box))
                .setPositiveButton("Add", (d, which) -> {
                    try {
                        int r = Integer.parseInt(root.getText().toString().trim());
                        if (r < 0 || r > 127) throw new NumberFormatException();
                        Preset p = Sf2Ops.addSampleAsPreset(sf, name.getText().toString(), w.pcm, w.rate, r,
                                w.loopStart, w.loopEnd);
                        tab = 0;
                        changed();
                        toast(String.format(Locale.US, "Added as preset %03d:%03d", p.bank, p.program));
                    } catch (NumberFormatException e) {
                        toast("Root key must be a number from 0 to 127.");
                    } catch (RuntimeException e) {
                        showError(e);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ save ("compile")

    private void startSave() {
        String prob = Sf2Writer.limitProblem(sf);
        if (prob != null) {
            showMessage("Can't save yet", prob);
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, safeName(sf.name) + ".sf2");
        startActivityForResult(i, REQ_SAVE);
    }

    private void saveTo(final Uri uri) {
        for (String u : session.uris) {
            if (u.equals(uri.toString())) {
                showMessage("Choose another file name",
                        "That file is still being read by this soundfont (its samples are streamed from it), "
                                + "so it can't be overwritten. Save under a new name.");
                return;
            }
        }
        runWithProgress("Saving...", p -> {
            OutputStream os = getContentResolver().openOutputStream(uri, "wt");
            if (os == null) throw new IOException("Could not write the file.");
            try {
                BufferedOutputStream bo = new BufferedOutputStream(os, 1 << 16);
                Sf2Writer.write(sf, bo, p);
                bo.flush();
            } finally {
                os.close();
            }
        }, () -> {
            dirty = false;
            refresh();
            toast("Saved.");
        }, null);
    }

    private void cleanUp() {
        confirm("Remove instruments that no preset uses, and samples that no instrument uses?", () -> {
            int[] r = Sf2Ops.removeUnused(sf);
            changed();
            toast("Removed " + r[0] + " instruments and " + r[1] + " samples.");
        });
    }

    private void renameSoundFont() {
        promptText("Soundfont name", sf.name, 255, text -> {
            sf.name = text.trim();
            changed();
        });
    }

    // ------------------------------------------------------------------ helpers

    private void runWithProgress(String title, final Work work, final Runnable onDone, final Runnable onFail) {
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setCancelable(false).create();
        dlg.show();
        exec.execute(() -> {
            try {
                work.run(frac -> ui.post(() -> {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) (frac * 1000));
                }));
                ui.post(() -> {
                    dlg.dismiss();
                    if (onDone != null) onDone.run();
                });
            } catch (final Throwable t) {
                ui.post(() -> {
                    dlg.dismiss();
                    if (onFail != null) onFail.run();
                    showError(t);
                });
            }
        });
    }

    private void showError(Throwable t) {
        String msg = t.getMessage();
        if (t instanceof OutOfMemoryError) {
            msg = "Out of memory. Try a smaller soundfont, or import fewer presets.";
        }
        if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
        showMessage("Something went wrong", msg);
    }

    private void showMessage(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void confirm(String msg, final Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("No", null).show();
    }

    private void confirmDiscard(final Runnable proceed) {
        if (!dirty) {
            proceed.run();
            return;
        }
        confirm("Discard your unsaved changes?", proceed);
    }

    private void promptText(String title, String initial, int maxLen, final TextResult result) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        et.setText(initial);
        et.setSelection(et.getText().length());
        LinearLayout box = form();
        box.addView(et);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("OK", (d, w) -> result.ok(et.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        return box;
    }

    private ScrollView scroll(View child) {
        ScrollView sv = new ScrollView(this);
        sv.addView(child);
        return sv;
    }

    private EditText field(LinearLayout box, String label, String value, boolean signed) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, 0);
        box.addView(tv);
        EditText et = new EditText(this);
        int type = InputType.TYPE_CLASS_NUMBER;
        if (signed) type |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        et.setInputType(type);
        et.setText(value);
        box.addView(et);
        return et;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        return name == null ? "sample" : name;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
        return s.isEmpty() ? "soundfont" : s;
    }
}
