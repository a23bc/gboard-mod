package com.a23bc.gboard9key;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
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
 * Gboard Chinese 9-key: make the enter key OCCUPY THE TWO BOTTOM-RIGHT SLOTS.
 *
 * Measured on PJZ110 / Gboard 18.6.3:
 *   key_pos_del             [1219,2132 213x202] delete      (right column row 1)
 *   key_pos_move_cursor     [1219,2334 213x202] keyA        (right column row 2)
 *   key_pos_shift           [1219,2536 213x202] shift       (right column row 3)
 *   key_pos_ime_action      [1217,2738 215x206] enter       (BOTTOM ROW, sibling container)
 *   row pitch = 202px
 *
 * The right column (3 vertical rows) and the bottom row are SIBLING containers,
 * so resizing enter always triggers a cross-container relayout (verified: enter
 * was pushed off-screen). Instead:
 *   1. remove keyA for real -> shift slides up into row 2
 *   2. keep the original enter instance but make it invisible (logic intact)
 *   3. add ONE real button on the keyboard root overlay covering
 *      y = enter.top - row .. enter.bottom, x = enter.left .. enter.right
 *   4. forward touches on that button to the original enter view
 * Result: delete | shift | one big enter key spanning two slots.
 *
 * Anchor: InputMethodService.setInputView(View), the only reliable framework
 * anchor on Gboard. Keys are found by resource id (not obfuscated).
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
        int row = shift.getHeight();                 // 202
        int[] e = location(enter);                   // [1217,2738]
        int[] r = location(root);                    // [0,0] on this device

        // 1) real delete -> shift slides up into row 2
        rightCol.removeView(keyA);

        // 2) original enter becomes invisible but keeps its touch/click logic
        enter.setAlpha(0f);

        // 3) the merged key: one View covering row 3 + row 4 on the right
        int left = e[0] - r[0];
        int top = e[1] - row - r[1];
        int w = enter.getWidth();
        int h = row + enter.getHeight();
        View big = buildBigEnter(root, enter);
        ViewGroup vg = (ViewGroup) root;
        boolean realChild = false;
        try {
            // Views added to ViewGroupOverlay are drawn but do NOT take part in
            // touch dispatch (verified: zero "forward" log lines after tapping).
            // A real child layer does, so the WHOLE two-slot button is clickable.
            android.widget.FrameLayout layer =
                    new android.widget.FrameLayout(root.getContext());
            vg.addView(layer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            android.widget.FrameLayout.LayoutParams blp =
                    new android.widget.FrameLayout.LayoutParams(w, h);
            blp.leftMargin = left;
            blp.topMargin = top;
            layer.addView(big, blp);
            realChild = true;
        } catch (Throwable t) {
            Log.w(TAG, "layer addView failed, falling back to overlay", t);
            vg.getOverlay().add(big);
            big.setLeft(left);
            big.setTop(top);
            big.setRight(left + w);
            big.setBottom(top + h);
        }
        big.bringToFront();

        Log.i(TAG, "big enter merged: [" + left + "," + top + " " + w + "x" + h + "]"
                + " row=" + row + " keyA removed=" + (keyA.getParent() == null)
                + " rightCol children=" + rightCol.getChildCount()
                + " realChild=" + realChild);
    }

    /** A real clickable button (not a decoration) that forwards to the original enter. */
    private static View buildBigEnter(View root, View enter) {
        TextView tv = new TextView(root.getContext());
        tv.setText("\u21B5");                       // ↵
        tv.setTextSize(34f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        boolean copied = false;
        try {
            Drawable bg = enter.getBackground();
            if (bg != null && bg.getConstantState() != null) {
                tv.setBackground(bg.getConstantState().newDrawable().mutate());
                copied = true;
            }
        } catch (Throwable ignored) {
            // fall through to a neutral fallback
        }
        if (!copied) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(0xFF3C4252);
            gd.setCornerRadius(24f);
            tv.setBackground(gd);
        }
        tv.setOnTouchListener((v, ev) -> forwardTo(enter, ev));
        Log.i(TAG, "big enter built, backgroundCopied=" + copied);
        return tv;
    }

    /** Re-dispatch a touch at the centre of the original enter view. */
    private static boolean forwardTo(View enter, MotionEvent ev) {
        MotionEvent copy = MotionEvent.obtain(
                ev.getDownTime(), ev.getEventTime(), ev.getAction(),
                enter.getWidth() / 2f, enter.getHeight() / 2f,
                ev.getMetaState());
        try {
            Log.i(TAG, "forward action=" + ev.getAction()
                    + " -> enter " + enter.getWidth() + "x" + enter.getHeight());
            enter.dispatchTouchEvent(copy);
        } catch (Throwable t) {
            Log.w(TAG, "forward failed", t);
        } finally {
            copy.recycle();
        }
        return true;
    }

    private static int[] location(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return loc;
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
