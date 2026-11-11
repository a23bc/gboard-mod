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
 * v0.1 - View-tree probe + (disabled) right-column tweak for Gboard Chinese 9-key.
 * Uses the modern libxposed API (compiled against 102.0.0, module.prop declares 101).
 *
 * Anchor: InputMethodService.setInputView(View). It is the only reliable framework
 * anchor on Gboard - Gboard overrides onCreateInputView/onStartInputView WITHOUT
 * calling super, so hooks on those never fire (see BetterZUIKey-GboardExt/PRINCIPLE.md,
 * verified on Gboard 17.2.2 / 18.1.3 / 18.3.1). setInputView is not overridden.
 *
 * No obfuscated class names are referenced. Keys are located by geometry
 * (right-most column sorted by y), so an R8 renames-everything update does not
 * break the module.
 *
 * Desired right column, top to bottom (PJZ110 / Gboard 18.6.3 screenshot):
 *   delete | 4-way arrow ("keyA") | shift(caps, with "123") | enter
 * Result:
 *   delete | shift | enter (two rows tall, swallowing keyA's slot)
 */
public final class HookMain extends XposedModule {

    private static final String TAG = "G9K";
    private static final String TARGET = "com.google.android.inputmethod.latin";

    /** v0.1: log the keyboard View tree only. */
    private static final boolean DUMP_TREE = true;
    /** Flip to true ONLY after the dump confirms 4 sibling key Views in the right column. */
    private static final boolean APPLY_TWEAK = false;

    // The 9-key keys sit ~16 levels deep under InputView; 12 truncated the dump
    // at the left symbol column (measured on Gboard 18.6.3 / PJZ110).
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
            // Tells us whether the installed LSPosed actually serves API 101 or 102.
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
     * Swap the 2nd/3rd right-column keys, hide the (now lower) keyA, then grow
     * the enter key upward by one row so it swallows that slot.
     */
    private static void applyTweak(List<KeyView> column) {
        View keyA = column.get(1).view;
        View shift = column.get(2).view;
        View enter = column.get(3).view;

        int topA = keyA.getTop();
        int topShift = shift.getTop();
        int row = Math.abs(topShift - topA);
        if (row < 8) {
            row = enter.getHeight();
        }

        // 1) swap positions using translation, which does not disturb the parent's layout.
        keyA.setTranslationY(topShift - topA);
        shift.setTranslationY(topA - topShift);

        // 2) keyA disappears but keeps its slot so the parent does not reflow.
        keyA.setAlpha(0f);
        keyA.setClickable(false);
        keyA.setEnabled(false);

        // 3) enter grows upward by one row. Prefer real LayoutParams; fall back to scaleY.
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

        Log.i(TAG, "tweak applied: row=" + row
                + " enter " + oldHeight + " -> " + (oldHeight + row)
                + " layoutParams=" + resized
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
            if (k.centerX < thresholdX) {
                continue;
            }
            if (k.width < rootWidth * 0.08 || k.height < 20) {
                continue;
            }
            if (k.visibility != View.VISIBLE) {
                continue;
            }
            column.add(k);
        }
        Collections.sort(column, Comparator.comparingInt(k -> k.top));
        return column;
    }

    private static void collect(View v, List<KeyView> out) {
        if (out.size() >= MAX_NODES) {
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

    private static void dumpTree(View root) {
        Log.i(TAG, "========== KEYBOARD VIEW TREE ==========");
        Log.i(TAG, "root=" + root.getClass().getName()
                + " " + root.getWidth() + "x" + root.getHeight());
        walk(root, 0, new int[1]);
        Log.i(TAG, "========== END VIEW TREE ==========");
        Log.i(TAG, "run: adb logcat -s G9K");
    }

    private static void walk(View v, int depth, int[] counter) {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return;
        }
        counter[0]++;
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
        int children = v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : -1;
        Log.i(TAG, String.format(
                "%s%s id=%s vis=%d click=%d [%d,%d %dx%d] desc=%s text=%s children=%d",
                indent(depth), v.getClass().getName(), id,
                v.getVisibility(), v.isClickable() ? 1 : 0,
                loc[0], loc[1], v.getWidth(), v.getHeight(),
                desc, text, children));
        // A GONE subtree never renders; skipping it keeps the dump focused and
        // leaves logcat buffer room for the actual 9-key rows.
        if (v.getVisibility() == View.GONE) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, counter);
            }
        }
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    private static final class KeyView {
        final View view;
        final int left;
        final int top;
        final int width;
        final int height;
        final int centerX;
        final int visibility;

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
        }

        String describe() {
            return view.getClass().getSimpleName()
                    + " [" + left + "," + top + " " + width + "x" + height + "]";
        }
    }
}
