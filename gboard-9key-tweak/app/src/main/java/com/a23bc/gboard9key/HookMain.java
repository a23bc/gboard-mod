package com.a23bc.gboard9key;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Gboard Chinese 9-key: ONE tall enter key occupying both bottom-right slots,
 * fully clickable, with NO stretched artwork.
 *
 * Measured on PJZ110 / Gboard 18.6.3 (window coords):
 *   key_pos_move_cursor     [1219,2334 213x202] keyA    (right column row 2)
 *   key_pos_shift           [1219,2536 213x202] shift   (right column row 3)
 *   key_pos_ime_action      [1217,2738 215x206] enter   (BOTTOM ROW, sibling container)
 *   row pitch = 202px
 *
 * Why this layered design (each earlier approach failed, see project memory):
 *  - LayoutParams.height -> cross-container relayout, enter pushed off screen
 *  - ViewGroupOverlay    -> drawn, but receives NO touch events
 *  - real child layer    -> InputView (custom ViewGroup) does not dispatch to it
 *  - scaleY on enter     -> glyph stretched; upper half unclickable because the
 *                           bottom-row parent never receives that touch
 *
 * Final:
 *  VISUAL = a newly drawn, UNSCALED key on the root overlay (draw only).
 *           Background copied from a real Gboard key when possible; the arrow
 *           is a normal-size centred TextView, so nothing is stretched.
 *  TOUCH  = hook dispatchTouchEvent along the InputView instance class chain.
 *           Hits inside the merged rect are forwarded to the ORIGINAL enter
 *           view (kept alive, alpha=0) and consumed, so the whole two-slot
 *           area works and the lower half is not handled twice.
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
    private static volatile Rect sHit;      // window coordinates
    private static volatile boolean sDispatchHooked = false;

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
                        int[] r = new int[2];
                        sRoot.getLocationInWindow(r);
                        float x = ev.getX() + r[0];
                        float y = ev.getY() + r[1];
                        if (!hit.contains((int) x, (int) y)) {
                            return chain.proceed();
                        }
                        int action = ev.getAction();
                        if (action == MotionEvent.ACTION_DOWN
                                || action == MotionEvent.ACTION_UP
                                || action == MotionEvent.ACTION_CANCEL) {
                            MotionEvent copy = MotionEvent.obtain(
                                    ev.getDownTime(), ev.getEventTime(), action,
                                    enter.getWidth() / 2f, enter.getHeight() / 2f,
                                    ev.getMetaState());
                            try {
                                enter.dispatchTouchEvent(copy);
                            } catch (Throwable t) {
                                Log.w(TAG, "enter dispatch failed", t);
                            } finally {
                                copy.recycle();
                            }
                            Log.i(TAG, "dispatch forward action=" + action
                                    + " at " + (int) x + "," + (int) y);
                        }
                        // Consume: prevents the bottom row handling it a 2nd time.
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
        int[] e = location(enter);
        int[] r = location(root);
        int left = e[0] - r[0];
        int top = e[1] - row - r[1];
        int w = enter.getWidth();
        int h = row + enter.getHeight();

        // 1) real delete -> shift slides up into row 2
        rightCol.removeView(keyA);

        // 2) original enter stays alive (logic + touch target) but invisible
        enter.setAlpha(0f);

        // 3) draw ONE unstretched key covering both slots
        View big = buildBigKey(root, shift, w, h);
        ((ViewGroup) root).getOverlay().add(big);
        big.setLeft(left);
        big.setTop(top);
        big.setRight(left + w);
        big.setBottom(top + h);

        // 4) the whole rect is clickable through the dispatch hook
        sEnter = enter;
        sHit = new Rect(e[0], e[1] - row, e[0] + w, e[1] + enter.getHeight());

        Log.i(TAG, "merged enter: visual [" + left + "," + top + " " + w + "x" + h + "]"
                + " hit=" + sHit + " row=" + row
                + " keyA removed=" + (keyA.getParent() == null)
                + " rightCol children=" + rightCol.getChildCount());
    }

    /** Rounded key with a normal-size centred glyph - nothing is scaled. */
    private static View buildBigKey(View root, View donor, int w, int h) {
        FrameLayout box = new FrameLayout(root.getContext());
        boolean copied = false;
        try {
            Drawable src = findBackground(donor);
            if (src != null && src.getConstantState() != null) {
                box.setBackground(src.getConstantState().newDrawable().mutate());
                copied = true;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        if (!copied) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(0xFF3C4252);
            gd.setCornerRadius(26f);
            box.setBackground(gd);
        }
        TextView glyph = new TextView(root.getContext());
        glyph.setText("\u21B5");
        glyph.setTextSize(34f);
        glyph.setTextColor(Color.WHITE);
        glyph.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        box.addView(glyph, lp);
        Log.i(TAG, "big key built " + w + "x" + h + " backgroundCopied=" + copied);
        return box;
    }

    private static Drawable findBackground(View v) {
        Drawable d = v.getBackground();
        if (d != null) {
            return d;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Drawable r = findBackground(g.getChildAt(i));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static int[] location(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
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
