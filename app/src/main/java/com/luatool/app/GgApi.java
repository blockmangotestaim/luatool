package com.luatool.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.File;
import java.util.List;

/**
 * A GameGuardian-style "gg" table for Lua scripts.
 * Implemented subset: see README. Anything not listed is simply absent (nil).
 */
final class GgApi {
    private GgApi() {}

    interface Fn { Varargs call(Varargs a) throws Exception; }

    private static void reg(LuaTable t, final String name, final Fn f) {
        t.set(name, new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                Session.check();
                try {
                    return f.call(a);
                } catch (LuaError e) {
                    throw e;
                } catch (MemEngine.Cancelled e) {
                    throw new LuaError("script stopped");
                } catch (InterruptedException e) {
                    throw new LuaError("script stopped");
                } catch (Exception e) {
                    throw new LuaError("gg." + name + ": " + e.getMessage());
                }
            }
        });
    }

    private static MemEngine eng() {
        MemEngine e = Session.engine;
        if (e == null) throw new LuaError("no process selected");
        return e;
    }

    private static String[] strList(LuaValue v) {
        LuaTable t = v.checktable();
        int n = t.length();
        String[] out = new String[n];
        for (int i = 1; i <= n; i++) out[i - 1] = t.get(i).tojstring();
        return out;
    }

    private static LuaValue toLua(Object v) {
        if (v == null) return LuaValue.NIL;
        if (v instanceof Double) return LuaValue.valueOf((Double) v);
        return LuaValue.valueOf((double) (Long) v);
    }

    private static boolean writeLua(MemEngine e, long addr, int type, LuaValue v) {
        LuaValue n = v.tonumber();
        if (n.isnil()) throw new LuaError("value is not a number");
        return e.writeNumber(addr, type, n.todouble(), n.tolong());
    }

    private static LuaTable resultRow(MemEngine e, long addr, int type) {
        Object v = e.readValue(addr, type);
        if (v == null) return null;
        LuaTable r = new LuaTable();
        r.set("address", LuaValue.valueOf((double) addr));
        r.set("value", toLua(v));
        r.set("flags", type);
        return r;
    }

    private static void doSearch(Varargs a, boolean refine) {
        MemEngine e = eng();
        String text = a.arg(1).tojstring();
        int type = a.optint(2, MemEngine.T_DWORD);
        int sign = a.optint(4, MemEngine.SIGN_EQUAL);
        int n;
        if (refine) {
            n = e.refine(text, type, sign);
        } else {
            long from = a.optlong(5, 0);
            long to = a.optlong(6, -1);
            int limit = a.optint(7, 0);
            n = e.search(text, type, sign, from, to < 0 ? Long.MAX_VALUE : to, limit);
        }
        AppLog.add((refine ? "refine '" : "search '") + text + "': " + n + " results");
    }

    static LuaTable install(final File script) {
        LuaTable gg = new LuaTable();

        // constants
        gg.set("TYPE_BYTE", MemEngine.T_BYTE);
        gg.set("TYPE_WORD", MemEngine.T_WORD);
        gg.set("TYPE_DWORD", MemEngine.T_DWORD);
        gg.set("TYPE_XOR", MemEngine.T_XOR);
        gg.set("TYPE_FLOAT", MemEngine.T_FLOAT);
        gg.set("TYPE_QWORD", MemEngine.T_QWORD);
        gg.set("TYPE_DOUBLE", MemEngine.T_DOUBLE);
        gg.set("REGION_C_HEAP", MemEngine.R_C_HEAP);
        gg.set("REGION_JAVA_HEAP", MemEngine.R_JAVA_HEAP);
        gg.set("REGION_C_ALLOC", MemEngine.R_C_ALLOC);
        gg.set("REGION_C_DATA", MemEngine.R_C_DATA);
        gg.set("REGION_C_BSS", MemEngine.R_C_BSS);
        gg.set("REGION_ANONYMOUS", MemEngine.R_ANON);
        gg.set("REGION_STACK", MemEngine.R_STACK);
        gg.set("REGION_ASHMEM", MemEngine.R_ASHMEM);
        gg.set("REGION_JAVA", MemEngine.R_JAVA);
        gg.set("REGION_CODE_APP", MemEngine.R_CODE_APP);
        gg.set("REGION_OTHER", MemEngine.R_OTHER);
        gg.set("REGION_ALL", MemEngine.R_ALL);
        gg.set("SIGN_EQUAL", MemEngine.SIGN_EQUAL);
        gg.set("SIGN_NOT_EQUAL", MemEngine.SIGN_NOT_EQUAL);
        gg.set("SIGN_GREATER_OR_EQUAL", MemEngine.SIGN_GREATER_OR_EQUAL);
        gg.set("SIGN_LESS_OR_EQUAL", MemEngine.SIGN_LESS_OR_EQUAL);
        gg.set("VERSION", "LuaTool 1.0");
        gg.set("BUILD", 1);

        Context app = Session.app;
        if (app != null) {
            gg.set("FILES_DIR", app.getFilesDir().getAbsolutePath());
            gg.set("TMP_PATH", app.getCacheDir().getAbsolutePath());
        }
        gg.set("EXT_STORAGE", Environment.getExternalStorageDirectory().getAbsolutePath());

        // ---------- UI ----------
        reg(gg, "alert", new Fn() { public Varargs call(Varargs a) {
            String b1 = a.isnil(2) ? null : a.arg(2).tojstring();
            String b2 = a.isnil(3) ? null : a.arg(3).tojstring();
            String b3 = a.isnil(4) ? null : a.arg(4).tojstring();
            return LuaValue.valueOf(UiBridge.alert(a.arg(1).tojstring(), b1, b2, b3));
        }});

        reg(gg, "toast", new Fn() { public Varargs call(Varargs a) {
            UiBridge.toast(a.arg(1).tojstring());
            return LuaValue.NONE;
        }});

        reg(gg, "choice", new Fn() { public Varargs call(Varargs a) {
            String[] items = strList(a.arg(1));
            int sel = a.optint(2, 0) - 1;
            String msg = a.isnil(3) ? null : a.arg(3).tojstring();
            int r = UiBridge.choice(items, sel, msg);
            return r < 0 ? LuaValue.NIL : LuaValue.valueOf(r + 1);
        }});

        reg(gg, "multiChoice", new Fn() { public Varargs call(Varargs a) {
            String[] items = strList(a.arg(1));
            boolean[] checked = new boolean[items.length];
            if (a.istable(2)) {
                LuaTable sel = a.checktable(2);
                for (int i = 0; i < items.length; i++) checked[i] = sel.get(i + 1).toboolean();
            }
            String msg = a.isnil(3) ? null : a.arg(3).tojstring();
            boolean[] r = UiBridge.multiChoice(items, checked, msg);
            if (r == null) return LuaValue.NIL;
            LuaTable out = new LuaTable();
            for (int i = 0; i < r.length; i++) if (r[i]) out.set(i + 1, LuaValue.TRUE);
            return out;
        }});

        reg(gg, "prompt", new Fn() { public Varargs call(Varargs a) {
            LuaTable prompts = a.checktable(1);
            LuaTable defs = a.istable(2) ? a.checktable(2) : new LuaTable();
            LuaTable types = a.istable(3) ? a.checktable(3) : new LuaTable();
            LuaValue[] keys = prompts.keys();
            int n = keys.length;
            String[] labels = new String[n], dv = new String[n], ty = new String[n];
            for (int i = 0; i < n; i++) {
                labels[i] = prompts.get(keys[i]).tojstring();
                LuaValue d = defs.get(keys[i]);
                dv[i] = d.isnil() ? "" : d.tojstring();
                LuaValue t = types.get(keys[i]);
                ty[i] = t.isnil() ? "text" : t.tojstring();
            }
            Object[] r = UiBridge.prompt(labels, dv, ty);
            if (r == null) return LuaValue.NIL;
            LuaTable out = new LuaTable();
            for (int i = 0; i < n; i++) {
                LuaValue v;
                if (r[i] instanceof Boolean) {
                    v = LuaValue.valueOf((Boolean) r[i]);
                } else if ("number".equals(ty[i])) {
                    try { v = LuaValue.valueOf(Double.parseDouble(((String) r[i]).trim())); }
                    catch (NumberFormatException ex) { v = LuaValue.NIL; }
                } else {
                    v = LuaValue.valueOf((String) r[i]);
                }
                out.set(keys[i], v);
            }
            return out;
        }});

        reg(gg, "sleep", new Fn() { public Varargs call(Varargs a) throws Exception {
            long end = System.currentTimeMillis() + a.optlong(1, 0);
            while (true) {
                Session.check();
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                Thread.sleep(Math.min(left, 50));
            }
            return LuaValue.NONE;
        }});

        reg(gg, "isVisible", new Fn() { public Varargs call(Varargs a) {
            return LuaValue.valueOf(OverlayService.panelVisible());
        }});
        reg(gg, "setVisible", new Fn() { public Varargs call(Varargs a) {
            OverlayService.setPanelVisible(a.arg(1).toboolean());
            return LuaValue.NONE;
        }});
        reg(gg, "showUiButton", new Fn() { public Varargs call(Varargs a) {
            OverlayService.setBubbleVisible(true);
            return LuaValue.NONE;
        }});
        reg(gg, "hideUiButton", new Fn() { public Varargs call(Varargs a) {
            OverlayService.setBubbleVisible(false);
            return LuaValue.NONE;
        }});
        reg(gg, "isClickedUiButton", new Fn() { public Varargs call(Varargs a) {
            Session.lastClickPoll = System.currentTimeMillis();
            boolean c = Session.clicked;
            Session.clicked = false;
            return LuaValue.valueOf(c);
        }});
        reg(gg, "copyText", new Fn() { public Varargs call(final Varargs a) {
            final Context c = Session.app;
            final String text = a.arg(1).tojstring();
            if (c != null) UiBridge.main.post(new Runnable() { public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("lua", text));
                } catch (Throwable ignored) {}
            }});
            return LuaValue.TRUE;
        }});
        reg(gg, "getFile", new Fn() { public Varargs call(Varargs a) {
            return LuaValue.valueOf(script.getAbsolutePath());
        }});
        reg(gg, "skipRestoreState", new Fn() { public Varargs call(Varargs a) { return LuaValue.NONE; } });

        // ---------- target ----------
        reg(gg, "getTargetInfo", new Fn() { public Varargs call(Varargs a) {
            ProcessInfo p = Session.target;
            if (p == null) return LuaValue.NIL;
            LuaTable t = new LuaTable();
            t.set("pid", p.pid);
            t.set("processName", p.name);
            t.set("packageName", p.name);
            t.set("label", p.name);
            return t;
        }});
        reg(gg, "getTargetPackage", new Fn() { public Varargs call(Varargs a) {
            ProcessInfo p = Session.target;
            return p == null ? LuaValue.NIL : LuaValue.valueOf(p.name);
        }});
        reg(gg, "processPause", new Fn() { public Varargs call(Varargs a) {
            ProcessInfo p = Session.target;
            if (p != null && p.pid != android.os.Process.myPid()) android.os.Process.sendSignal(p.pid, 19);
            return LuaValue.TRUE;
        }});
        reg(gg, "processResume", new Fn() { public Varargs call(Varargs a) {
            ProcessInfo p = Session.target;
            if (p != null && p.pid != android.os.Process.myPid()) android.os.Process.sendSignal(p.pid, 18);
            return LuaValue.TRUE;
        }});

        // ---------- ranges ----------
        reg(gg, "setRanges", new Fn() { public Varargs call(Varargs a) {
            eng().ranges = a.optint(1, MemEngine.DEFAULT_RANGES);
            return LuaValue.NONE;
        }});
        reg(gg, "getRanges", new Fn() { public Varargs call(Varargs a) {
            return LuaValue.valueOf(eng().ranges);
        }});
        reg(gg, "getRangesList", new Fn() { public Varargs call(Varargs a) {
            String filter = a.optjstring(1, "");
            List<MemEngine.Region> rs = eng().regions(MemEngine.R_ALL, 0, Long.MAX_VALUE, false);
            LuaTable out = new LuaTable();
            int k = 1;
            for (MemEngine.Region r : rs) {
                if (!filter.isEmpty() && !r.path.contains(filter)) continue;
                LuaTable t = new LuaTable();
                t.set("start", LuaValue.valueOf((double) r.start));
                t.set("end", LuaValue.valueOf((double) r.end));
                t.set("type", r.perms);
                t.set("name", r.path);
                t.set("internalName", r.path);
                t.set("state", "O");
                out.set(k++, t);
            }
            return out;
        }});

        // ---------- search / results ----------
        reg(gg, "searchNumber", new Fn() { public Varargs call(Varargs a) { doSearch(a, false); return LuaValue.NONE; } });
        reg(gg, "refineNumber", new Fn() { public Varargs call(Varargs a) { doSearch(a, true); return LuaValue.NONE; } });
        reg(gg, "clearResults", new Fn() { public Varargs call(Varargs a) { eng().clearResults(); return LuaValue.NONE; } });
        reg(gg, "getResultsCount", new Fn() { public Varargs call(Varargs a) { return LuaValue.valueOf(eng().count()); } });

        reg(gg, "getResults", new Fn() { public Varargs call(Varargs a) {
            MemEngine e = eng();
            int cnt = a.optint(1, 100);
            int skip = a.optint(2, 0);
            boolean hasMin = !a.isnil(3), hasMax = !a.isnil(4), hasType = !a.isnil(7);
            long amin = hasMin ? a.arg(3).tolong() : 0, amax = hasMax ? a.arg(4).tolong() : Long.MAX_VALUE;
            int type = hasType ? a.arg(7).toint() : 0;
            LuaTable out = new LuaTable();
            int k = 1;
            for (int i = skip; i < e.count() && k <= cnt; i++) {
                long addr = e.addrAt(i);
                int t = e.typeAt(i);
                if (addr < amin || addr > amax) continue;
                if (hasType && (t & type) == 0) continue;
                LuaTable row = resultRow(e, addr, t);
                if (row != null) out.set(k++, row);
            }
            return out;
        }});

        reg(gg, "editAll", new Fn() { public Varargs call(Varargs a) {
            MemEngine e = eng();
            int type = a.optint(2, MemEngine.T_DWORD);
            int ok = 0;
            for (int i = 0; i < e.count(); i++) {
                if ((i & 0x3FF) == 0) Session.check();
                if (writeLua(e, e.addrAt(i), type, a.arg(1))) ok++;
            }
            AppLog.add("editAll: wrote " + ok + " of " + e.count());
            return LuaValue.valueOf(ok);
        }});

        reg(gg, "getValues", new Fn() { public Varargs call(Varargs a) {
            MemEngine e = eng();
            LuaTable in = a.checktable(1);
            LuaTable out = new LuaTable();
            int n = in.length();
            for (int i = 1; i <= n; i++) {
                LuaValue it = in.get(i);
                long addr = it.get("address").tolong();
                int type = it.get("flags").optint(MemEngine.T_DWORD);
                LuaTable row = resultRow(e, addr, type);
                if (row == null) {
                    row = new LuaTable();
                    row.set("address", LuaValue.valueOf((double) addr));
                    row.set("flags", type);
                }
                out.set(i, row);
            }
            return out;
        }});

        reg(gg, "setValues", new Fn() { public Varargs call(Varargs a) {
            MemEngine e = eng();
            LuaTable in = a.checktable(1);
            int n = in.length();
            boolean all = true;
            for (int i = 1; i <= n; i++) {
                LuaValue it = in.get(i);
                long addr = it.get("address").tolong();
                int type = it.get("flags").optint(MemEngine.T_DWORD);
                if (!writeLua(e, addr, type, it.get("value"))) all = false;
            }
            return LuaValue.valueOf(all);
        }});

        // ---------- freeze (subset of GG's saved list) ----------
        reg(gg, "addListItems", new Fn() { public Varargs call(Varargs a) {
            MemEngine e = eng();
            LuaTable in = a.checktable(1);
            int n = in.length();
            for (int i = 1; i <= n; i++) {
                LuaValue it = in.get(i);
                if (!it.get("freeze").toboolean()) continue;
                LuaValue v = it.get("value").tonumber();
                if (v.isnil()) continue;
                int type = it.get("flags").optint(MemEngine.T_DWORD);
                e.freeze(it.get("address").tolong(), type, v.todouble(), v.tolong());
            }
            return LuaValue.TRUE;
        }});
        reg(gg, "removeListItems", new Fn() { public Varargs call(Varargs a) {
            MemEngine e = eng();
            LuaTable in = a.checktable(1);
            int n = in.length();
            for (int i = 1; i <= n; i++) e.unfreeze(in.get(i).get("address").tolong());
            return LuaValue.TRUE;
        }});
        reg(gg, "clearList", new Fn() { public Varargs call(Varargs a) { eng().clearFreeze(); return LuaValue.NONE; } });

        return gg;
    }
}
