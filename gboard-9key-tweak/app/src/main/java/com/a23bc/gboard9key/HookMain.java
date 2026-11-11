package com.a23bc.gboard9key;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Gboard Chinese 9-key right-column tweak, modern libxposed API (102).
 *
 * Anchor: InputMethodService.setInputView(View) - the only reliable framework
 * anchor on Gboard (onCreateInputView/onStartInputView are overridden without
 * calling super, so hooks on them never fire).
 *
 * Keys are located by RESOURCE ID, which is not obfuscated. Measured on
 * PJZ110 / Gboard 18.6.3 (logs/g9k-20261112-031239.txt):
 *   key_pos_del             [1219,2132 213x202] delete
 *   key_pos_move_cursor     [1219,2334 213x202] 4-way arrow ("keyA")
 *   key_pos_shift           [1219,2536 213x202] caps / number mode
 *   key_pos_ime_action      [1217,2738 215x206] enter
 *   row pitch = 202px
 *
 * Desired: shift <-> keyA swap, hide keyA, grow enter upward by one row so it
 * swallows keyA's former slot. Result: delete | shift | enter (two rows tall).
 */
public final class HookMain extends XposedModule {

    private static final String TAG = "G9K";
    private static final String TARGET = "com.google.android.inputmethod.latin";

    private static final String ID_KEY_A = "key_pos_move_cursor";
    private static final String ID_SHIFT = "key_pos_shift";
    private static final String ID_ENTER = "key_pos_ime_action";

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
        View keyA = findKeyById(root, ID_KEY_A);
        View shift = findKeyById(root, ID_SHIFT);
        View enter = findKeyById(root, ID_ENTER);
        if (keyA == null || shift == null || enter == null) {
            Log.w(TAG, "id lookup failed: keyA=" + (keyA != null)
                    + " shift=" + (shift != null) + " enter=" + (enter != null));
            return;
        }
        synchronized (APPLIED) {
            if (APPLIED.containsKey(enter)) {
                return;
            }
            APPLIED.put(enter, Boolean.TRUE);
        }
        applyTweak(keyA, shift, enter);
    }

    /**
     * REAL merge: keyA is removed from the right column (so shift naturally
     * moves up into its slot), and the enter key is reparented from the bottom
     * row into the right column. One key ends up where two used to be.
     *
     * The two keys live in SIBLING containers - right column is child #3 of
     * input_area (3 vertical rows), enter is in the bottom row container.
     * That is why changing height/translation could never work: it triggers a
     * cross-container relayout. Reparenting avoids touching any layout size.
     */
    private static void applyTweak(View keyA, View shift, View enter) {
        ViewGroup rightCol = keyA.getParent() instanceof ViewGroup
                ? (ViewGroup) keyA.getParent() : null;
        ViewGroup bottomRow = enter.getParent() instanceof ViewGroup
                ? (ViewGroup) enter.getParent() : null;
        if (rightCol == null || bottomRow == null || rightCol == bottomRow) {
            Log.w(TAG, "unexpected parents: rightCol=" + rightCol
                    + " bottomRow=" + bottomRow);
            return;
        }
        try {
            // Reuse keyA's layout params so enter gets the exact same slot
            // (width / height / weight) in the right column.
            ViewGroup.LayoutParams lp =
                    rightCol.generateLayoutParams(keyA.getLayoutParams());
            rightCol.removeView(keyA);       // real delete -> shift slides up
            bottomRow.removeView(enter);     // leave the bottom row
            rightCol.addView(enter, lp);     // the merged key
            Log.i(TAG, "merged: keyA removed; enter reparented"
                    + " rightCol=" + rightCol.getClass().getSimpleName()
                    + " children=" + rightCol.getChildCount()
                    + " bottomRow children=" + bottomRow.getChildCount()
                    + " shift=" + shift.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.w(TAG, "merge failed", t);
        }
    }

    /** Resource entry names are plain text even when class names are R8-mangled. */
    private static View findKeyById(View root, String idName) {
        return findKeyById(root, idName, new int[1]);
    }

    private static View findKeyById(View v, String idName, int[] counter) {
        if (counter[0]++ > MAX_NODES || v.getVisibility() == View.GONE) {
            return null;
        }
        try {
            if (v.getId() != View.NO_ID
                    && idName.equals(v.getResources().getResourceEntryName(v.getId()))) {
                return v;
            }
        } catch (Throwable ignored) {
            // resource name not resolvable for this id
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findKeyById(g.getChildAt(i), idName, counter);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Log only clickable keys, batched: ~36 lines instead of ~1500. */
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
}
