package com.a23bc.gboard9key;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Gboard Chinese 9-key: ONE tall enter key occupying both bottom-right slots.
 * ONLY while the Chinese 9-key layout is on screen.
 *
 * A state machine driven by OnPreDrawListener is essential: switching to
 * QWERTY / symbol / number keyboards reuses the same InputView, so a one-shot
 * setInputView hook leaves the merged key (and its touch rect) behind - the
 * "giant enter key on other keyboards" bug.
 *
 * Colour: Gboard resolves color_gm3_* tokens in style_sheet_dynamic_color_*.
 * We SAMPLE the rendered pixel of a real key, so it follows dynamic colour.
 * Glyph: the real VectorDrawable is picked out of the original enter subtree
 * (the first candidate is a GradientDrawable -1x-1, not the glyph).
 * Alignment: geometry from the right column, not the bottom row.
 * Touch: hook dispatchTouchEvent on the InputView class chain, hit-test in
 * screen coords, forward a complete MotionEvent copy to the original enter.
 */
public final class HookMain extends XposedModule {

    private static final String TAG = "G9K";
    private static final String TARGET = "com.google.android.inputmethod.latin";

    private static final String ID_KEY_A = "key_pos_move_cursor";
    private static final String ID_SHIFT = "key_pos_shift";
    private static final String ID_ENTER = "key_pos_ime_action";
    private static final String ID_9KEY_1 = "key_pos_9key_1";

    private static final boolean DUMP_TREE = true;
    private static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 24;
    private static final long DUMP_INTERVAL_MS = 1500L;

    private static long lastDumpMs = 0L;

    private static volatile View sRoot;
    private static volatile View sEnter;
    private static volatile Rect sHit;          // screen coordinates
    private static volatile boolean sDispatchHooked = false;
    private static volatile boolean sForwarding = false;

    // State machine: applied only while a 9-key layout is visible.
    private static volatile boolean sApplied = false;
    private static volatile boolean sPressed = false;
    private static volatile Rect sShiftRect;   // screen coords, never steal it
    private static volatile View sWatchedRoot;
    private static volatile View sBigKey;
    private static volatile View sHiddenEnter;
    private static volatile View sHiddenKeyA;
    private static volatile ViewGroup sOverlayRoot;

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
            // Secondary anchor. setInputView can fire BEFORE our hook is
            // installed on a cold start; onStartInput runs on every focus
            // change and lets us recover the current InputView via mInputView.
            Method onStartInput = svc.getMethod("onStartInput",
                    EditorInfo.class, boolean.class);
            hook(onStartInput).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Field f = svc.getDeclaredField("mInputView");
                    f.setAccessible(true);
                    Object iv = f.get(chain.getThisObject());
                    if (iv instanceof View) {
                        final View root = (View) iv;
                        root.post(() -> {
                            try {
                                onKeyboardView(root);
                            } catch (Throwable t) {
                                Log.w(TAG, "onStartInput inspect failed", t);
                            }
                        });
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "onStartInput probe failed", t);
                }
                return result;
            });
            Log.i(TAG, "hooks installed (setInputView + onStartInput), pid="
                    + android.os.Process.myPid());
        } catch (Throwable t) {
            Log.w(TAG, "hook install failed", t);
        }
    }

    private void onKeyboardView(View root) {
        if (!(root instanceof ViewGroup) || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        sRoot = root;
        long now = System.currentTimeMillis();
        if (DUMP_TREE && now - lastDumpMs > DUMP_INTERVAL_MS) {
            lastDumpMs = now;
            dumpTree(root);
        }
        installDispatchHook(root);
        installPreDrawWatcher(root);
        evaluate(root);
    }

    /** Layout switches re-draw the same InputView; re-evaluate every frame. */
    private void installPreDrawWatcher(View root) {
        if (sWatchedRoot == root) {
            return;
        }
        sWatchedRoot = root;
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            try {
                evaluate(root);
            } catch (Throwable t) {
                Log.w(TAG, "predraw evaluate failed", t);
            }
            return true;
        });
        Log.i(TAG, "predraw watcher installed");
    }

    private static void evaluate(View root) {
        boolean is9Key = findKeyById(root, ID_9KEY_1) != null
                && findKeyById(root, ID_ENTER) != null;
        if (is9Key == sApplied) {
            return;
        }
        if (!is9Key) {
            cleanup();
            Log.i(TAG, "left 9-key layout: merged key removed, enter restored");
            return;
        }
        View keyA = findKeyById(root, ID_KEY_A);
        View shift = findKeyById(root, ID_SHIFT);
        View enter = findKeyById(root, ID_ENTER);
        if (keyA == null || shift == null || enter == null) {
            return;
        }
        applyTweak(root, keyA, shift, enter);
    }

    private static void cleanup() {
        if (sOverlayRoot != null && sBigKey != null) {
            try {
                sOverlayRoot.getOverlay().remove(sBigKey);
            } catch (Throwable ignored) {
                // root already detached
            }
        }
        if (sHiddenEnter != null) {
            sHiddenEnter.setAlpha(1f);
        }
        if (sHiddenKeyA != null) {
            sHiddenKeyA.setVisibility(View.VISIBLE);
        }
        sBigKey = null;
        sHiddenEnter = null;
        sHiddenKeyA = null;
        sOverlayRoot = null;
        sEnter = null;
        sHit = null;
        sShiftRect = null;
        sPressed = false;
        sApplied = false;
    }

    private static void invalidateBigKey() {
        View v = sBigKey;
        if (v != null) {
            v.postInvalidate();
        }
    }

    private void installDispatchHook(View root) {
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
                        Object a0 = chain.getArg(0);
                        if (!sApplied || chain.getThisObject() != sRoot
                                || !(a0 instanceof MotionEvent)) {
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
                        Rect shiftRect = sShiftRect;
                        if (shiftRect != null && shiftRect.contains((int) x, (int) y)) {
                            return chain.proceed();   // never steal the shift key
                        }
                        if (!hit.contains((int) x, (int) y)) {
                            return chain.proceed();
                        }
                        int action = ev.getAction();
                        if (action == MotionEvent.ACTION_DOWN) {
                            sPressed = true;
                            invalidateBigKey();
                        } else if (action == MotionEvent.ACTION_UP
                                || action == MotionEvent.ACTION_CANCEL) {
                            sPressed = false;
                            invalidateBigKey();
                            if (action == MotionEvent.ACTION_UP) {
                                // Native action WITHOUT forwarding a touch:
                                // forwarding made Gboard paint its pressed
                                // state on the wrong (shift) key.
                                enter.performClick();
                            }
                        }
                        Log.i(TAG, "merged key action=" + action
                                + " at " + (int) x + "," + (int) y
                                + " pressed=" + sPressed);
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
            return;
        }
        int row = shift.getHeight();
        int origH = enter.getHeight();
        int[] e = location(enter);
        int[] r = location(root);
        int[] rc = location(rightCol);

        int left = rc[0] - r[0];
        int top = rc[1] + 2 * row - r[1];
        int w = rightCol.getWidth();
        int h = row + origH;

        // Sample colour AND the real key-background edges before we alter the
        // tree: Gboard keys are inset ~10px and the bottom row leaves ~41px.
        int sampled = sampleKeyColor(root, shift);
        int color = sampled != 0 ? sampled : keyColor(root.getContext());
        int[] insets = measureKeyInsets(root, left, top, w, h, color);

        // GONE (reversible) instead of removeView: LinearLayout still slides
        // shift up, and cleanup() can restore it when the layout changes.
        keyA.setVisibility(View.GONE);
        enter.setAlpha(0f);

        View big = buildBigKey(root, enter, w, h, color, insets);
        ((ViewGroup) root).getOverlay().add(big);
        big.layout(left, top, left + w, top + h);

        sOverlayRoot = (ViewGroup) root;
        sBigKey = big;
        sHiddenEnter = enter;
        sHiddenKeyA = keyA;
        sEnter = enter;
        // Hit-test only the REAL painted background, not the whole layout rect.
        int bigTopScreen = rc[1] + 2 * row;
        sHit = new Rect(rc[0] + insets[0], bigTopScreen + insets[1],
                        rc[0] + insets[2], bigTopScreen + insets[3]);
        // shift slid up into row 2 when keyA went GONE; exclude it explicitly.
        sShiftRect = new Rect(rc[0], rc[1] + row,
                              rc[0] + w, rc[1] + 2 * row);
        sPressed = false;
        sApplied = true;

        Log.i(TAG, "9-key active: merged key [" + left + "," + top + " "
                + w + "x" + h + "] color=0x" + Integer.toHexString(color)
                + " sampled=" + (sampled != 0)
                + " bg=[" + insets[0] + "," + insets[1] + ","
                + insets[2] + "," + insets[3] + "] hit=" + sHit);
    }

    /** Paint a real key into a 1x1 canvas and read the resolved theme colour. */
    private static int sampleKeyColor(View root, View key) {
        try {
            int[] k = new int[2];
            int[] r = new int[2];
            key.getLocationOnScreen(k);
            root.getLocationOnScreen(r);
            int bx = k[0] - r[0];
            int by = k[1] - r[1];
            int w = key.getWidth();
            int h = key.getHeight();
            int[][] pts = {
                    {bx + 24, by + 24}, {bx + w - 24, by + 24},
                    {bx + 24, by + h - 24}, {bx + w / 2, by + 24}
            };
            Map<Integer, Integer> votes = new HashMap<>();
            for (int[] p : pts) {
                Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.translate(-p[0], -p[1]);
                root.draw(c);
                int col = bmp.getPixel(0, 0);
                bmp.recycle();
                if (Color.alpha(col) == 255) {
                    Integer n = votes.get(col);
                    votes.put(col, n == null ? 1 : n + 1);
                }
            }
            int best = 0;
            int max = 0;
            for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
                if (e.getValue() > max) {
                    max = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        } catch (Throwable t) {
            Log.w(TAG, "sampleKeyColor failed", t);
            return 0;
        }
    }

    /** Find the REAL key-background edges (insets) by scanning rendered pixels. */
    private static int[] measureKeyInsets(View root, int left, int top,
                                          int w, int h, int bg) {
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.translate(-left, -top);
            root.draw(c);
            int[] res = new int[]{0, 0, w - 1, h - 1};
            int probeY = 70;                 // inside row 3, clear of the corner
            int midX = w / 2;
            for (int x = 0; x < w; x++) {
                if (sameColor(bmp.getPixel(x, probeY), bg)) { res[0] = x; break; }
            }
            for (int x = w - 1; x >= 0; x--) {
                if (sameColor(bmp.getPixel(x, probeY), bg)) { res[2] = x; break; }
            }
            for (int y = 0; y < h; y++) {
                if (sameColor(bmp.getPixel(midX, y), bg)) { res[1] = y; break; }
            }
            for (int y = h - 1; y >= 0; y--) {
                if (sameColor(bmp.getPixel(midX, y), bg)) { res[3] = y; break; }
            }
            bmp.recycle();
            // Sanity: a failed scan would produce a full-bleed rectangle again.
            if (res[2] - res[0] < w / 2 || res[3] - res[1] < h / 2) {
                return new int[]{10, 0, w - 11, h - 41};
            }
            return res;
        } catch (Throwable t) {
            Log.w(TAG, "measureKeyInsets failed", t);
            return new int[]{10, 0, w - 11, h - 41};
        }
    }

    private static boolean sameColor(int a, int b) {
        return Color.alpha(a) == 255
                && Math.abs(Color.red(a) - Color.red(b)) <= 16
                && Math.abs(Color.green(a) - Color.green(b)) <= 16
                && Math.abs(Color.blue(a) - Color.blue(b)) <= 16;
    }

    /** One plain View; background + native icon drawn directly (no children). */
    private static View buildBigKey(View root, View enter, int w, int h,
                                    int color, int[] insets) {
        Context c = root.getContext();
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        // Top corners match the right-column keys (~24px); bottom corners
        // inherit the original oval/capsule enter key's large roundness.
        gd.setCornerRadii(new float[]{
                24, 24,      // top-left
                24, 24,      // top-right
                88, 88,      // bottom-right: ONLY this corner is large
                24, 24       // bottom-left, same as the other three
        });
        final int baseColor = color;
        final int bl = insets[0], bt = insets[1], br = insets[2], bb = insets[3];

        List<Drawable> cands = new ArrayList<>();
        collectIcons(enter, cands);
        Drawable chosen = null;
        for (Drawable d : cands) {
            if (chosen == null && d.getIntrinsicWidth() > 0
                    && d.getIntrinsicHeight() > 0) {
                chosen = d;
            }
        }
        Drawable tmp = chosen;
        if (chosen != null && chosen.getConstantState() != null) {
            try {
                tmp = chosen.getConstantState().newDrawable().mutate();
            } catch (Throwable ignored) {
                tmp = chosen;
            }
        }
        final Drawable nativeIcon = tmp;

        View key = new View(c) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (!sApplied) {
                    return;             // defensive: never draw on other layouts
                }
                // Explicit inset bounds + our own pressed-state feedback
                // (Gboard's own pressed state is not available to overlay views).
                gd.setColor(sPressed ? pressedColor(baseColor) : baseColor);
                gd.setBounds(bl, bt, br, bb);
                gd.draw(canvas);
                int cx = (bl + br) / 2;
                int cy = (bt + bb) / 2;
                if (nativeIcon != null) {
                    int iw = nativeIcon.getIntrinsicWidth();
                    int ih = nativeIcon.getIntrinsicHeight();
                    float s = 96f / Math.max(iw, ih);
                    int dw = Math.round(iw * s);
                    int dh = Math.round(ih * s);
                    int l = cx - dw / 2;
                    int t = cy - dh / 2;
                    nativeIcon.setBounds(l, t, l + dw, t + dh);
                    nativeIcon.draw(canvas);
                } else {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Color.WHITE);
                    p.setTextSize(90f);
                    p.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("\u21B5", cx, cy + 30f, p);
                }
            }
        };
        // No setBackground(): drawn at inset bounds inside onDraw.
        key.setWillNotDraw(false);
        Log.i(TAG, "big key " + w + "x" + h
                + " color=0x" + Integer.toHexString(color)
                + " iconCandidates=" + cands.size()
                + " chosen=" + (nativeIcon == null ? "null"
                    : nativeIcon.getClass().getSimpleName()));
        return key;
    }

    /** Gboard dynamic-colour keys lighten when pressed. */
    private static int pressedColor(int c) {
        return Color.rgb(
                Math.min(255, Color.red(c) + 26),
                Math.min(255, Color.green(c) + 26),
                Math.min(255, Color.blue(c) + 28));
    }

    private static void collectIcons(View v, List<Drawable> out) {
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d != null) {
                out.add(d);
            }
        }
        if (v instanceof TextView) {
            for (Drawable d : ((TextView) v).getCompoundDrawables()) {
                if (d != null) {
                    out.add(d);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectIcons(g.getChildAt(i), out);
            }
        }
    }

    /** Fallback only when pixel sampling fails. */
    private static int keyColor(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return c.getColor(android.R.color.system_neutral2_800);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return 0xFF3C4252;
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
