package com.luatool.app;

import android.content.Context;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/** Global state: selected process, running script, stop flag. */
public final class Session {
    private Session() {}

    public static volatile ProcessInfo target;
    public static volatile MemEngine engine;
    public static volatile boolean running;
    public static volatile boolean stopRequested;
    public static volatile File scriptFile;
    public static volatile Context app;

    // floating-button "click" support for scripts that poll gg.isClickedUiButton()
    public static volatile long lastClickPoll;
    public static volatile boolean clicked;

    private static Thread thread;

    /** @return null on success, else an error message. */
    public static synchronized String select(ProcessInfo p) {
        MemEngine old = engine;
        engine = null;
        if (old != null) old.close();
        target = p;
        MemEngine e = new MemEngine(p.pid);
        String err = e.open();
        if (err != null) {
            e.close();
            return err;
        }
        engine = e;
        return null;
    }

    public static void check() {
        if (stopRequested) throw new LuaError("script stopped");
    }

    public static synchronized void run(final File f) {
        if (running) {
            AppLog.add("A script is already running - press Stop first.");
            return;
        }
        final ProcessInfo t = target;
        final MemEngine eng = engine;
        if (t == null || eng == null) {
            AppLog.add("Select a process that opened successfully first.");
            return;
        }
        if (!new File("/proc/" + t.pid).exists()) {
            AppLog.add("That process has ended. Refresh the list and select it again.");
            return;
        }
        running = true;
        stopRequested = false;
        clicked = false;
        eng.cancel = false;
        scriptFile = f;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                AppLog.add("Running " + f.getName() + " on " + t.name);
                try {
                    String src = readUtf8(f);
                    if (src.startsWith("\uFEFF")) src = src.substring(1);
                    if (src.startsWith("#")) src = "--" + src;
                    Globals g = JsePlatform.standardGlobals();
                    g.STDOUT = new PrintStream(new LogOut(), true);
                    g.set("luajava", LuaValue.NIL);   // no raw Java access from scripts
                    g.set("gg", GgApi.install(f));
                    LuaValue chunk = g.load(src, "@" + f.getName());
                    chunk.call();
                    AppLog.add("Script finished." + (eng.frozenCount() > 0 ? " (" + eng.frozenCount() + " values frozen - Stop clears them)" : ""));
                } catch (LuaError e) {
                    if (stopRequested) AppLog.add("Script stopped.");
                    else AppLog.add("Lua error: " + e.getMessage());
                } catch (Throwable e) {
                    if (stopRequested || e instanceof MemEngine.Cancelled) AppLog.add("Script stopped.");
                    else AppLog.add("Error: " + e);
                } finally {
                    running = false;
                }
            }
        }, "lua-script");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        MemEngine e = engine;
        if (e != null) {
            e.cancel = true;
            e.clearFreeze();
        }
        if (running) {
            stopRequested = true;
            Thread t = thread;
            if (t != null) t.interrupt();
            AppLog.add("Stopping...");
        } else {
            AppLog.add("Stopped (frozen values cleared).");
        }
    }

    static String readUtf8(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /** Routes Lua print() to the panel log. */
    private static final class LogOut extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override public void write(int b) {
            if (b == '\n') flushLine();
            else if (b != '\r') buf.write(b);
        }

        private void flushLine() {
            try { AppLog.add(buf.toString("UTF-8")); } catch (Exception ignored) {}
            buf.reset();
        }
    }
}
