package com.a23bc.gboard9key;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Gboard Chinese 9-key right-column tweak, modern libxposed API (102).
 *
 * Anchor: InputMethodService.setInputView(View) - the only reliable framework
 * anchor on Gboard (onCreateInputView/onStartInputView are overridden without
 * calling super, so hooks on them never fire; verified on 17.2.2/18.1.3/18.3.1).
 *
 * Keys are found by geometry + clickability, never by obfuscated class names.
 * Measured on PJZ110 / Gboard 18.6.3:
 *   keyboard starts at y=2100, right column x=1219..1440,
 *   9-key rows are 202px tall, letter keys carry id key_pos_9key_1..9.
 *
 * Right column (top to bottom): delete | 4-way arrow ("keyA") | shift | enter.
 * Desired: shift <-> keyA swap, hide keyA, grow enter upward by one row.
 */
public final class HookMain extends XposedModule {

    private static final String TAG = "G9K";
    private static final String TARGET = "com.google.android.inputmethod.latin";

    private static final boolean DUMP_TREE = true;
    private static final boolean APPLY_TWEAK = true;

    private static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 24;
    private static final long DUMP_INTERVAL_MS = 1500L;

    private static long lastDumpMs = 0L;
    private static final WeakHashMap<View, Boolean> APPLIED = new WeakHashMap<>();

    public HookMain() {
        super();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        try {
            Log.i(TAG, "framework=" + getFrameworkName()
                    + " version=" + getFrameworkVersion()
                    + " api=" + getApiVersion()
                    + " pkg=" + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "framework info unavailable", t);
        }
        if (!TARGET.equals(pkg)) {
            return;
        }
        try {
            Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService",
                    false,
                    param.getClassLoader());
            Method setInputView = svc.getDeclaredMethod("setInputView", View.class);
            setInputView.setAccessible(true);
            hook(setInputView).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    try {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof View) {
                            final View root = (View) arg0;
                            root.post(() -> {
                                try {
                                    onKeyboardView(root);
                                } catch (Throwable t) {
                                    // Never let a hook crash the IME.
                                    Log.w(TAG, "inspect failed", t);
                                }
                            });
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "setInputView after-hook failed", t);
                    }
                }
            });
            Log.i(TAG, "hook installed on setInputView, pid=" + android.os.Process.myPid());
        } catch (Throwable t) {
            Log.w(TAG, "hook install failed", t);
        }
    }

    private static void onKeyboardView(View root) {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            Log.w(TAG, "root not laid out: " + root.getWidth() + "x" + root.getHeight());
            return;
        }
        long now = System.currentTimeMillis();
        if (DUMP_TREE && now - lastDumpMs > DUMP_INTERVAL_MS) {
            lastDumpMs = now;
            dumpTree(root);
        }
        if (!APPLY_TWEAK) {
            return;
        }
        List<KeyView> column = findRightColumn(root);
        Log.i(TAG, "right-column candidates: " + column.size());
        for (KeyView k : column) {
            Log.i(TAG, "  " + k.describe());
        }
        if (column.size() < 4) {
            Log.w(TAG, "need 4 right-column keys, skip tweak");
            return;
        }
        View enter = column.get(column.size() - 1).view;
        synchronized (APPLIED) {
            if (APPLIED.containsKey(enter)) {
                return;
            }
            APPLIED.put(enter, Boolean.TRUE);
        }
        applyTweak(column);
    }

    /**
     * The header toolbar also has a clickable key on the right (voice input),
     * so the right column is the LAST FOUR clickable keys sorted by y.
     */
    private static void applyTweak(List<KeyView> column) {
        int n = column.size();
        View delete = column.get(n - 4).view;
        View keyA = column.get(n - 3).view;   // 4-way arrow
        View shift = column.get(n - 2).view;  // caps / shift
        View enter = column.get(n - 1).view;  // enter / newline

        Log.i(TAG, "targets: delete=" + column.get(n - 4).describe()
                + " keyA=" + column.get(n - 3).describe()
                + " shift=" + column.get(n - 2).describe()
                + " enter=" + column.get(n - 1).describe());

        int topA = keyA.getTop();
        int topShift = shift.getTop();
        int row = Math.abs(topShift - topA);
        if (row < 8) {
            row = enter.getHeight();
        }

        // 1) swap via translation; does not disturb the parent's layout pass.
        keyA.setTranslationY(topShift - topA);
        shift.setTranslationY(topA - topShift);

        // 2) keyA disappears but keeps its slot, so the parent does not reflow.
        keyA.setAlpha(0f);
        keyA.setClickable(false);
        keyA.setEnabled(false);

        // 3) enter grows upward by one row. Prefer LayoutParams, else scaleY.
        int oldHeight = enter.getHeight();
        ViewGroup.LayoutParams lp = enter.getLayoutParams();
        boolean resized = false;
        if (lp != null && lp.height > 0 && oldHeight > 0) {
            lp.height = oldHeight + row;
            enter.setLayoutParams(lp);
            resized = true;
        } else if (oldHeight > 0) {
            enter.setScaleY((float) (oldHeight + row) / oldHeight);
        }
        enter.setTranslationY(-row / 2f);
        enter.bringToFront();

        Log.i(TAG, "tweak applied: row=" + row
                + " enter " + oldHeight + " -> " + (oldHeight + row)
                + " layoutParams=" + resized
                + " delete=" + delete.getClass().getSimpleName()
                + " parent=" + enter.getParent().getClass().getName());
    }

    private static List<KeyView> findRightColumn(View root) {
        List<KeyView> all = new ArrayList<>();
        collect(root, all);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);
        int rootWidth = root.getWidth();
        int thresholdX = rootLoc[0] + (int) (rootWidth * 0.78f);

        List<KeyView> column = new ArrayList<>();
        for (KeyView k : all) {
            // Only real keys are clickable; containers are not.
            if (!k.clickable || k.visibility != View.VISIBLE) {
                continue;
            }
            if (k.centerX < thresholdX) {
                continue;
            }
            if (k.width < rootWidth * 0.08 || k.height < 20) {
                continue;
            }
            column.add(k);
        }
        Collections.sort(column, Comparator.comparingInt(k -> k.top));
        return column;
    }

    private static void collect(View v, List<KeyView> out) {
        if (out.size() >= MAX_NODES || v.getVisibility() == View.GONE) {
            return;
        }
        out.add(new KeyView(v));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    /** Log only clickable keys, batched: ~30 lines instead of ~1500. */
    private static void dumpTree(View root) {
        Log.i(TAG, "========== KEYBOARD VIEW TREE ==========");
        Log.i(TAG, "root=" + root.getClass().getName()
                + " " + root.getWidth() + "x" + root.getHeight());
        List<String> lines = new ArrayList<>();
        walk(root, 0, new int[1], lines);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String l : lines) {
            sb.append(l).append('\n');
            if (++n % 12 == 0) {
                Log.i(TAG, sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            Log.i(TAG, sb.toString());
        }
        Log.i(TAG, "========== END VIEW TREE (" + lines.size() + " keys) ==========");
    }

    private static void walk(View v, int depth, int[] counter, List<String> out) {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return;
        }
        counter[0]++;
        if (v.getVisibility() == View.GONE) {
            return;
        }
        if (v.getVisibility() == View.VISIBLE && v.isClickable()
                && v.getWidth() > 0 && v.getHeight() > 0) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            String id = "";
            try {
                if (v.getId() != View.NO_ID) {
                    id = v.getResources().getResourceEntryName(v.getId());
                }
            } catch (Throwable ignored) {
                id = "0x" + Integer.toHexString(v.getId());
            }
            String desc = v.getContentDescription() == null
                    ? "" : v.getContentDescription().toString().replace('\n', ' ');
            String text = v instanceof TextView
                    ? String.valueOf(((TextView) v).getText()).replace('\n', ' ') : "";
            out.add(v.getClass().getSimpleName() + " id=" + id
                    + " [" + loc[0] + "," + loc[1] + " "
                    + v.getWidth() + "x" + v.getHeight() + "]"
                    + " desc=" + desc + " text=" + text);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, counter, out);
            }
        }
    }

    private static final class KeyView {
        final View view;
        final int left;
        final int top;
        final int width;
        final int height;
        final int centerX;
        final int visibility;
        final boolean clickable;
        final String desc;

        KeyView(View v) {
            this.view = v;
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            this.left = loc[0];
            this.top = loc[1];
            this.width = v.getWidth();
            this.height = v.getHeight();
            this.centerX = left + width / 2;
            this.visibility = v.getVisibility();
            this.clickable = v.isClickable();
            this.desc = v.getContentDescription() == null
                    ? "" : v.getContentDescription().toString();
        }

        String describe() {
            return view.getClass().getSimpleName()
                    + " [" + left + "," + top + " " + width + "x" + height + "]"
                    + " desc=" + desc;
        }
    }
}
