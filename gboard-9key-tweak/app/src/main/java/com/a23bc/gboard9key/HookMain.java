package com.a23bc.gboard9key;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Gboard Chinese 9-key: ONE tall enter key occupying both bottom-right slots.
 *
 * Measured on PJZ110 / Gboard 18.6.3 (window coords; screen coords are +160px):
 *   key_pos_move_cursor     [1219,2334 213x202] keyA    (right column row 2)
 *   key_pos_shift           [1219,2536 213x202] shift   (right column row 3)
 *   key_pos_ime_action      [1217,2738 215x206] enter   (BOTTOM ROW, sibling container)
 *   row pitch = 202px
 *
 * IMPORTANT FINDING: Gboard key BACKGROUNDS ARE NOT View.getBackground().
 * They are painted by the keyboard container, so reusing the native enter view
 * in an overlay yields the native glyph but NO key background (verified:
 * screenshot gboard-native-key.png). findBackground() returns null everywhere.
 *
 * Therefore the merged key is drawn as its own FrameLayout:
 *  - geometry taken from the RIGHT COLUMN (not the bottom row) so edges align
 *  - background colour read at RUNTIME from the system dynamic-colour palette,
 *    so it follows Material You / system colour changes on every keyboard build
 *  - glyph = the ORIGINAL enter icon drawable, at intrinsic size, centred
 *    (never scaled). layout() is used instead of setLeft/Top so children are
 *    actually laid out and the icon is visible.
 *  - touches: original enter stays alive (alpha=0) in the bottom row; hook
 *    dispatchTouchEvent on the InputView instance chain, hit-test the merged
 *    rect in SCREEN coords and forward a complete MotionEvent copy.
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

    private static volatile View sRoot;
    private static volatile View sEnter;
    private static volatile Rect sHit;      // screen coordinates
    private static volatile boolean sDispatchHooked = false;
    private static volatile boolean sForwarding = false;

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

    private void onKeyboardView(View root) {
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
            Log.w(TAG, "id lookup failed");
            return;
        }
        synchronized (APPLIED) {
            if (APPLIED.containsKey(enter)) {
                return;
            }
            APPLIED.put(enter, Boolean.TRUE);
        }
        installDispatchHook(root);
        applyTweak(root, keyA, shift, enter);
    }

    /** Hook dispatchTouchEvent on every class in the instance chain that declares it. */
    private void installDispatchHook(View root) {
        sRoot = root;
        if (sDispatchHooked) {
            return;
        }
        int n = 0;
        for (Class<?> c = root.getClass(); c != null && c != Object.class;
             c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    try {
                        Object self = chain.getThisObject();
                        Object a0 = chain.getArg(0);
                        if (self != sRoot || !(a0 instanceof MotionEvent)) {
                            return chain.proceed();
                        }
                        MotionEvent ev = (MotionEvent) a0;
                        Rect hit = sHit;
                        View enter = sEnter;
                        if (hit == null || enter == null) {
                            return chain.proceed();
                        }
                        float x = ev.getRawX();
                        float y = ev.getRawY();
                        if (!hit.contains((int) x, (int) y)) {
                            return chain.proceed();
                        }
                        int action = ev.getAction();
                        if (sForwarding) {
                            return chain.proceed();
                        }
                        if (action == MotionEvent.ACTION_DOWN
                                || action == MotionEvent.ACTION_UP
                                || action == MotionEvent.ACTION_CANCEL) {
                            sForwarding = true;
                            try {
                                MotionEvent copy = MotionEvent.obtain(ev);
                                copy.setLocation(enter.getWidth() / 2f,
                                        enter.getHeight() / 2f);
                                boolean handled;
                                try {
                                    handled = enter.dispatchTouchEvent(copy);
                                } finally {
                                    copy.recycle();
                                }
                                if (!handled && action == MotionEvent.ACTION_UP) {
                                    enter.performClick();
                                }
                                Log.i(TAG, "dispatch forward action=" + action
                                        + " at " + (int) x + "," + (int) y
                                        + " handled=" + handled);
                            } finally {
                                sForwarding = false;
                            }
                        }
                        return Boolean.TRUE;
                    } catch (Throwable t) {
                        Log.w(TAG, "dispatch intercept failed", t);
                        return chain.proceed();
                    }
                });
                n++;
            } catch (NoSuchMethodException ignored) {
                // this level does not declare dispatchTouchEvent
            } catch (Throwable t) {
                Log.w(TAG, "dispatch hook failed on " + c.getName(), t);
            }
        }
        sDispatchHooked = n > 0;
        Log.i(TAG, "dispatchTouchEvent hooked on " + n + " class(es)");
    }

    private static void applyTweak(View root, View keyA, View shift, View enter) {
        ViewGroup rightCol = keyA.getParent() instanceof ViewGroup
                ? (ViewGroup) keyA.getParent() : null;
        if (rightCol == null) {
            Log.w(TAG, "keyA has no ViewGroup parent");
            return;
        }
        int row = shift.getHeight();
        int origH = enter.getHeight();
        int[] e = location(enter);       // screen
        int[] r = location(root);        // screen
        int[] rc = location(rightCol);   // screen

        // Align to the RIGHT COLUMN (bottom-row key is 2px off, wrong width).
        int left = rc[0] - r[0];
        int top = rc[1] + 2 * row - r[1];
        int w = rightCol.getWidth();
        int h = row + origH;

        // 1) real delete -> shift slides up into row 2
        rightCol.removeView(keyA);

        // 2) original enter stays in the bottom row (avoids a reflow that
        //    pushed the period key into the bottom-right corner).
        enter.setAlpha(0f);

        // 3) draw the merged key. layout() (not setLeft/Top) so children are
        //    laid out and the native glyph actually renders.
        View big = buildBigKey(root, enter, w, h);
        ((ViewGroup) root).getOverlay().add(big);
        big.layout(left, top, left + w, top + h);

        // 4) whole rect clickable via the dispatch hook (screen coords)
        sEnter = enter;
        sHit = new Rect(rc[0], e[1] - row, rc[0] + w, e[1] + origH);

        Log.i(TAG, "merged key: [" + left + "," + top + " " + w + "x" + h + "]"
                + " hit=" + sHit + " row=" + row + " origH=" + origH
                + " keyA removed=" + (keyA.getParent() == null));
    }

    /** Rounded themed key with the ORIGINAL enter glyph at intrinsic size. */
    private static View buildBigKey(View root, View enter, int w, int h) {
        Context c = root.getContext();
        FrameLayout box = new FrameLayout(c);
        int color = keyColor(c);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(26f);
        box.setBackground(gd);

        Drawable icon = findIcon(enter);
        if (icon != null) {
            ImageView iv = new ImageView(c);
            try {
                iv.setImageDrawable(icon.getConstantState() != null
                        ? icon.getConstantState().newDrawable().mutate() : icon);
            } catch (Throwable t) {
                iv.setImageDrawable(icon);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            box.addView(iv, lp);
        } else {
            TextView tv = new TextView(c);
            tv.setText("\u21B5");
            tv.setTextSize(34f);
            tv.setTextColor(Color.WHITE);
            tv.setGravity(Gravity.CENTER);
            box.addView(tv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        Log.i(TAG, "big key " + w + "x" + h
                + " color=0x" + Integer.toHexString(color)
                + " nativeIcon=" + (icon != null));
        return box;
    }

    /** Material You tonal palette - re-read on every keyboard build. */
    private static int keyColor(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return c.getColor(android.R.color.system_neutral1_800);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return 0xFF3C4252;
    }

    private static Drawable findIcon(View v) {
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d != null) {
                return d;
            }
        }
        if (v instanceof TextView) {
            for (Drawable d : ((TextView) v).getCompoundDrawables()) {
                if (d != null) {
                    return d;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Drawable d = findIcon(g.getChildAt(i));
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    /** Screen coordinates - matches MotionEvent.getRawX()/getRawY(). */
    private static int[] location(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc;
    }

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
            // id not resolvable
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
