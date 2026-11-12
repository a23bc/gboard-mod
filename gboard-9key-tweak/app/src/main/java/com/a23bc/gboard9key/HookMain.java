package com.a23bc.gboard9key;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Gboard Chinese 9-key: make the ORIGINAL enter key occupy the two bottom-right
 * slots (row 3 right + row 4 right).
 *
 * Measured on PJZ110 / Gboard 18.6.3:
 *   key_pos_del             [1219,2132 213x202] delete      (right column row 1)
 *   key_pos_move_cursor     [1219,2334 213x202] keyA        (right column row 2)
 *   key_pos_shift           [1219,2536 213x202] shift       (right column row 3)
 *   key_pos_ime_action      [1217,2738 215x206] enter       (BOTTOM ROW, sibling container)
 *   row pitch = 202px
 *
 * The right column (3 vertical rows) and the bottom row are SIBLING containers.
 * Changing LayoutParams.height triggers a cross-container relayout (verified:
 * enter was pushed off-screen). New views added to the root/overlay do not get
 * touch events on this InputView implementation (verified: zero forwarded
 * touches after tapping the upper half).
 *
 * Final approach: keep the ORIGINAL enter view - it already owns the correct
 * touch/click logic - and grow it upward with a draw/hit transform:
 *   pivotY = bottom, scaleY = (h + row) / h
 * This does not touch any layout, so no relayout happens, and Android's
 * isTransformedTouchPointInView() applies the child matrix, so the whole
 * two-slot area is clickable. Ancestors need clipChildren=false so the grown
 * part is not clipped by the bottom-row container.
 *
 * keyA is removed for real, so shift slides up into row 2.
 * Result: delete | shift | one tall enter key spanning rows 3 and 4.
 *
 * Anchor: InputMethodService.setInputView(View). Keys located by resource id.
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
        if (!(root instanceof ViewGroup) || root.getWidth() <= 0 || root.getHeight() <= 0) {
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
        applyTweak(root, keyA, shift, enter);
    }

    private static void applyTweak(View root, View keyA, View shift, View enter) {
        ViewGroup rightCol = keyA.getParent() instanceof ViewGroup
                ? (ViewGroup) keyA.getParent() : null;
        if (rightCol == null) {
            Log.w(TAG, "keyA has no ViewGroup parent");
            return;
        }
        int row = shift.getHeight();       // 202
        int h = enter.getHeight();         // 206
        float scale = (h + row) / (float) h;

        // 1) real delete -> shift slides up into row 2
        rightCol.removeView(keyA);

        // 2) grow the ORIGINAL enter upward by one row; bottom edge stays put.
        //    Draw/hit transform only: no LayoutParams change, no relayout.
        enter.setPivotY(h);
        enter.setScaleY(scale);
        enter.bringToFront();

        // 3) let the grown part draw (and be hit-tested) outside its parent.
        int levels = 0;
        ViewParent p = enter.getParent();
        while (p instanceof ViewGroup && levels < 8) {
            ViewGroup vg = (ViewGroup) p;
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
            p = vg.getParent();
            levels++;
        }

        Log.i(TAG, "enter spans two slots: h=" + h + " row=" + row
                + " scale=" + scale + " keyA removed=" + (keyA.getParent() == null)
                + " rightCol children=" + rightCol.getChildCount()
                + " clipDisabledLevels=" + levels
                + " enterTop=" + enter.getTop() + " enterBottom=" + enter.getBottom());
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
