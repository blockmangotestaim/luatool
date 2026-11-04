package com.luatool.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.EditText;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    static volatile MainActivity current;
    private static final int REQ_IMPORT = 1;

    private PanelView panel;
    private TextView infoView;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.app = getApplicationContext();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        int pad = Ui.dp(this, 8);
        root.setPadding(pad, pad, pad, pad);

        final TextView info = new TextView(this);
        infoView = info;
        info.setTextColor(0xFFCCCCCC);
        info.setTextSize(12);
        info.setText("1) Allow 'Display over other apps'  2) Start floating menu  3) Import / paste a .lua  "
                + "4) pick a process + script and press Run. You can also use the panel below without the overlay.");
        root.addView(info);

        LinearLayout r1 = new LinearLayout(this);
        r1.addView(btn("Overlay permission", new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlaySettings(); }
        }), w());
        r1.addView(btn("Start floating menu", new View.OnClickListener() {
            @Override public void onClick(View v) { startOverlay(); }
        }), w());
        r1.addView(btn("Stop menu", new View.OnClickListener() {
            @Override public void onClick(View v) { stopService(new Intent(MainActivity.this, OverlayService.class)); }
        }), w());
        root.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.addView(btn("Import .lua file", new View.OnClickListener() {
            @Override public void onClick(View v) { importFile(); }
        }), w());
        r2.addView(btn("Import from path", new View.OnClickListener() {
            @Override public void onClick(View v) { importFromPath(); }
        }), w());
        r2.addView(btn("Paste script from clipboard", new View.OnClickListener() {
            @Override public void onClick(View v) { pasteScript(); }
        }), w());
        root.addView(r2);

        panel = new PanelView(this);
        panel.setOnClose(new Runnable() { @Override public void run() { finish(); } });
        root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyOrientation();
    }

    private void applyOrientation() {
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (infoView != null) infoView.setVisibility(land ? View.GONE : View.VISIBLE);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation();
    }

    private LinearLayout.LayoutParams w() {
        return new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1f);
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onResume() {
        super.onResume();
        current = this;
        panel.refreshAll();
    }

    @Override protected void onPause() {
        if (current == this) current = null;
        super.onPause();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, "Open Settings > Apps > Special access > Display over other apps and allow this app (or the host, e.g. BlackBox).", Toast.LENGTH_LONG).show();
        }
    }

    private void startOverlay() {
        try {
            startService(new Intent(this, OverlayService.class));
        } catch (Throwable t) {
            AppLog.add("Could not start floating menu: " + t.getMessage());
        }
    }

    private void importFile() {
        ensureStoragePermission();
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            AppLog.add("File picker not available here - use 'Paste script from clipboard' instead.");
        }
    }

    /** Some environments (virtual spaces) cannot reach system content providers; the URI often contains the real path. */
    private static File pathFromUri(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) return new File(uri.getPath());
            String id = DocumentsContract.getDocumentId(uri);
            if (id.startsWith("raw:")) return new File(id.substring(4));
            if (id.startsWith("primary:")) return new File(Environment.getExternalStorageDirectory(), id.substring(8));
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void ensureStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, 2);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        // 1) direct file path hidden in the URI (works without any content provider)
        File direct = pathFromUri(uri);
        if (direct != null) {
            if (copyIn(direct)) return;
        }
        // 2) normal content resolver
        try {
            String name = null;
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) name = c.getString(idx);
                    }
                } finally {
                    c.close();
                }
            }
            File f = ScriptStore.save(this, name, getContentResolver().openInputStream(uri));
            AppLog.add("Imported " + f.getName());
            panel.refreshScripts();
        } catch (Throwable t) {
            AppLog.add("Import via picker failed: " + t.getMessage());
            if (direct != null) AppLog.add("Tried path " + direct + " - allow Storage permission for the host app, or use 'Import from path' / 'Paste'.");
            else AppLog.add("Use 'Import from path' (e.g. /sdcard/Download/script.lua) or 'Paste script from clipboard'.");
        }
    }

    /** Copy a real file (or all .lua/.txt files in a folder) into the script store. */
    private boolean copyIn(File f) {
        try {
            if (f.isDirectory()) {
                File[] fs = f.listFiles();
                int n = 0;
                if (fs != null) {
                    for (File x : fs) {
                        String low = x.getName().toLowerCase(Locale.US);
                        if (x.isFile() && (low.endsWith(".lua") || low.endsWith(".txt"))) {
                            ScriptStore.save(this, x.getName(), new java.io.FileInputStream(x));
                            n++;
                        }
                    }
                }
                AppLog.add("Imported " + n + " script(s) from " + f);
                panel.refreshScripts();
                return n > 0;
            }
            if (!f.isFile()) {
                AppLog.add("Not found: " + f);
                return false;
            }
            File out = ScriptStore.save(this, f.getName(), new java.io.FileInputStream(f));
            AppLog.add("Imported " + out.getName());
            panel.refreshScripts();
            return true;
        } catch (Throwable t) {
            AppLog.add("Could not read " + f + ": " + t.getMessage());
            return false;
        }
    }

    private void importFromPath() {
        ensureStoragePermission();
        final EditText et = new EditText(this);
        et.setSingleLine();
        et.setText("/sdcard/Download/");
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Path to a .lua file or a folder")
                .setView(et)
                .setPositiveButton("Import", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        copyIn(new File(et.getText().toString().trim()));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pasteScript() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm.getPrimaryClip();
            CharSequence text = (cd != null && cd.getItemCount() > 0) ? cd.getItemAt(0).coerceToText(this) : null;
            if (text == null || text.toString().trim().isEmpty()) {
                AppLog.add("Clipboard is empty - copy your Lua script text first.");
                return;
            }
            String name = "pasted_" + new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date()) + ".lua";
            File f = ScriptStore.saveText(this, name, text.toString());
            AppLog.add("Saved clipboard as " + f.getName());
            panel.refreshScripts();
        } catch (Throwable t) {
            AppLog.add("Paste failed: " + t.getMessage());
        }
    }
}
