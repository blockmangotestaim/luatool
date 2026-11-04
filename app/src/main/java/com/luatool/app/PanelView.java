package com.luatool.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The main control panel (overlay window or inline in the activity).
 * Responsive: lists sit side by side when the panel is wide (landscape) and the controls shrink when it is short.
 * In the overlay it can be moved (drag the title) and resized (drag the "Size" grip).
 */
public class PanelView extends LinearLayout {
    private static final int BG = 0xF0202124, SEL = 0xFF3949AB, TXT = 0xFFFFFFFF, DIM = 0xFFB0B0B0;

    public interface Host {
        void move(int dx, int dy);
        void resize(int dw, int dh);
        void done();
    }

    private final Context ctx;
    private final TextView logView;
    private final ScrollView logScroll;
    private final LinearLayout listsBox, col1, col2, buttonRow, header;
    private final TextView grip;
    private final SelAdapter procAdapter = new SelAdapter();
    private final SelAdapter scriptAdapter = new SelAdapter();
    private final List<Button> btns = new ArrayList<>();
    private List<ProcessInfo> procs = new ArrayList<>();
    private List<File> scripts = new ArrayList<>();
    private Runnable onClose;
    private Host host;
    private boolean layoutInit, lastWide, lastCompact;

    private final AppLog.Listener logListener = new AppLog.Listener() {
        @Override public void onChanged() {
            UiBridge.main.post(new Runnable() {
                @Override public void run() { updateLog(); }
            });
        }
    };

    private static final class SelAdapter extends BaseAdapter {
        List<String> items = new ArrayList<>();
        int selected = -1;
        int textSp = 13;

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            TextView tv = (v instanceof TextView) ? (TextView) v : new TextView(parent.getContext());
            int p = Ui.dp(parent.getContext(), textSp <= 11 ? 5 : 8);
            tv.setPadding(p, p, p, p);
            tv.setTextSize(textSp);
            tv.setTextColor(TXT);
            tv.setText(items.get(i));
            tv.setBackgroundColor(i == selected ? SEL : Color.TRANSPARENT);
            return tv;
        }
    }

    public PanelView(Context context) {
        super(context);
        this.ctx = context;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        int pad = Ui.dp(context, 6);
        setPadding(pad, pad, pad, pad);

        // header (drag to move)
        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("LuaTool", 15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        header.addView(button("X", new OnClickListener() {
            @Override public void onClick(View v) { if (onClose != null) onClose.run(); }
        }), new LayoutParams(Ui.dp(context, 44), Ui.dp(context, 36)));
        header.setOnTouchListener(new OnTouchListener() {
            float lx, ly;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (host == null) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lx = e.getRawX(); ly = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        host.move((int) (e.getRawX() - lx), (int) (e.getRawY() - ly));
                        lx = e.getRawX(); ly = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        host.done();
                        return true;
                }
                return false;
            }
        });
        addView(header);

        // processes + scripts
        ListView pl = new ListView(context);
        pl.setAdapter(procAdapter);
        pl.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int i, long id) { selectProcess(i); }
        });
        ListView sl = new ListView(context);
        sl.setAdapter(scriptAdapter);
        sl.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int i, long id) {
                scriptAdapter.selected = i;
                scriptAdapter.notifyDataSetChanged();
            }
        });
        col1 = column("1. Pick a process", "Refresh", new OnClickListener() {
            @Override public void onClick(View v) { refreshProcesses(true); }
        }, pl);
        col2 = column("2. Pick a .lua script", "Refresh", new OnClickListener() {
            @Override public void onClick(View v) { refreshScripts(true); }
        }, sl);
        listsBox = new LinearLayout(context);
        listsBox.setOrientation(VERTICAL);
        listsBox.addView(col1, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        listsBox.addView(col2, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(listsBox, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // run / stop / resize grip
        buttonRow = new LinearLayout(context);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        buttonRow.addView(button("Run", new OnClickListener() {
            @Override public void onClick(View v) { runSelected(); }
        }), new LayoutParams(0, Ui.dp(context, 40), 1f));
        buttonRow.addView(button("Stop", new OnClickListener() {
            @Override public void onClick(View v) { Session.stop(); }
        }), new LayoutParams(0, Ui.dp(context, 40), 1f));
        grip = label("Size", 12);
        grip.setGravity(Gravity.CENTER);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xFF444444);
        gb.setCornerRadius(Ui.dp(context, 6));
        grip.setBackground(gb);
        grip.setVisibility(GONE);
        grip.setOnTouchListener(new OnTouchListener() {
            float lx, ly;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (host == null) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lx = e.getRawX(); ly = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        host.resize((int) (e.getRawX() - lx), (int) (e.getRawY() - ly));
                        lx = e.getRawX(); ly = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        host.done();
                        return true;
                }
                return false;
            }
        });
        LayoutParams glp = new LayoutParams(Ui.dp(context, 56), Ui.dp(context, 40));
        glp.leftMargin = Ui.dp(context, 6);
        buttonRow.addView(grip, glp);
        addView(buttonRow);

        // log
        logView = new TextView(context);
        logView.setTextSize(11);
        logView.setTextColor(DIM);
        logScroll = new ScrollView(context);
        logScroll.addView(logView);
        addView(logScroll, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 80)));
    }

    public void setOnClose(Runnable r) { onClose = r; }

    /** Enables move/resize gestures (overlay mode). */
    public void setHost(Host h) {
        host = h;
        grip.setVisibility(h == null ? GONE : VISIBLE);
    }

    private TextView label(String s, int sp) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(TXT);
        return t;
    }

    private Button button(String text, OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int p = Ui.dp(ctx, 6);
        b.setPadding(p, 0, p, 0);
        b.setOnClickListener(l);
        btns.add(b);
        return b;
    }

    private LinearLayout column(String title, String btn, OnClickListener l, ListView lv) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(VERTICAL);
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(title, 12);
        t.setTextColor(DIM);
        r.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        r.addView(button(btn, l), new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 36)));
        c.addView(r);
        c.addView(lv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        return c;
    }

    // ---------- responsive layout ----------

    @Override protected void onSizeChanged(final int w, final int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        post(new Runnable() {
            @Override public void run() { applyLayout(w, h); }
        });
    }

    private void applyLayout(int w, int h) {
        boolean wide = w > h * 1.15f && w > Ui.dp(ctx, 360);
        boolean compact = h < Ui.dp(ctx, 340);
        if (layoutInit && wide == lastWide && compact == lastCompact) return;
        layoutInit = true;
        lastWide = wide;
        lastCompact = compact;

        listsBox.setOrientation(wide ? HORIZONTAL : VERTICAL);
        LayoutParams lp1 = wide ? new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f) : new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        LayoutParams lp2 = wide ? new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f) : new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        if (wide) lp2.leftMargin = Ui.dp(ctx, 6);
        col1.setLayoutParams(lp1);
        col2.setLayoutParams(lp2);

        int bh = Ui.dp(ctx, compact ? 30 : 38);
        for (Button b : btns) {
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp != null) { lp.height = bh; b.setLayoutParams(lp); }
            b.setTextSize(compact ? 11 : 13);
        }
        ViewGroup.LayoutParams gl = grip.getLayoutParams();
        gl.height = bh;
        grip.setLayoutParams(gl);

        int sp = compact ? 11 : 13;
        procAdapter.textSp = sp;
        scriptAdapter.textSp = sp;
        procAdapter.notifyDataSetChanged();
        scriptAdapter.notifyDataSetChanged();

        ViewGroup.LayoutParams ll = logScroll.getLayoutParams();
        ll.height = Ui.dp(ctx, compact ? 44 : 80);
        logScroll.setLayoutParams(ll);
        requestLayout();
    }

    // ---------- actions ----------

    public void refreshAll() {
        refreshProcesses(false);
        refreshScripts(false);
        updateLog();
    }

    public void refreshProcesses(boolean verbose) {
        int keepPid = -1;
        if (procAdapter.selected >= 0 && procAdapter.selected < procs.size()) keepPid = procs.get(procAdapter.selected).pid;
        procs = ProcessInfo.list();
        procAdapter.items = new ArrayList<>();
        procAdapter.selected = -1;
        for (int i = 0; i < procs.size(); i++) {
            procAdapter.items.add(procs.get(i).label());
            if (procs.get(i).pid == keepPid) procAdapter.selected = i;
        }
        procAdapter.notifyDataSetChanged();
        if (procs.isEmpty()) AppLog.add("No processes visible. Run this inside the virtual environment next to the app you want to edit.");
        else if (verbose) AppLog.add("Processes: " + procs.size());
    }

    public void refreshScripts(boolean verbose) {
        File keep = (scriptAdapter.selected >= 0 && scriptAdapter.selected < scripts.size()) ? scripts.get(scriptAdapter.selected) : null;
        scripts = ScriptStore.list(ctx);
        List<String> items = new ArrayList<>();
        int sel = -1;
        for (int i = 0; i < scripts.size(); i++) {
            items.add(ScriptStore.label(scripts.get(i)));
            if (keep != null && scripts.get(i).equals(keep)) sel = i;
        }
        if (scripts.isEmpty()) items.add("(no scripts yet - Import / Paste in the app, or drop .lua files into /sdcard/LuaTool)");
        scriptAdapter.items = items;
        scriptAdapter.selected = sel;
        scriptAdapter.notifyDataSetChanged();
        if (verbose) AppLog.add("Scripts: " + scripts.size() + " found in " + ScriptStore.dir(ctx));
    }

    public void refreshScripts() { refreshScripts(false); }

    private void selectProcess(int i) {
        if (i < 0 || i >= procs.size()) return;
        procAdapter.selected = i;
        procAdapter.notifyDataSetChanged();
        ProcessInfo p = procs.get(i);
        String err = Session.select(p);
        if (err == null) AppLog.add("Attached to " + p.name + " (pid " + p.pid + ") - memory is accessible.");
        else AppLog.add("Cannot use " + p.name + ": " + err);
    }

    private void runSelected() {
        if (Session.engine == null) {
            AppLog.add("Pick a process first (it must say 'memory is accessible').");
            return;
        }
        int i = scriptAdapter.selected;
        if (i < 0 || i >= scripts.size()) {
            AppLog.add("Pick a script first.");
            return;
        }
        Session.run(scripts.get(i));
    }

    private void updateLog() {
        logView.setText(AppLog.dump());
        logScroll.post(new Runnable() {
            @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AppLog.addListener(logListener);
        updateLog();
    }

    @Override protected void onDetachedFromWindow() {
        AppLog.removeListener(logListener);
        super.onDetachedFromWindow();
    }
}
