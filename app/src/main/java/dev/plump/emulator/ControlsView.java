package dev.plump.emulator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** One-screen GBA touch controls. Pointer IDs are independent and always released on CANCEL. */
final class ControlsView extends View {
    interface Listener { void buttonsChanged(int mask); }
    private static final int B = 0, SELECT = 2, START = 3, UP = 4, DOWN = 5;
    private static final int LEFT = 6, RIGHT = 7, A = 8, L = 10, R = 11;
    private final ButtonInputState pointers = new ButtonInputState();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private int mask;

    ControlsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(Color.rgb(28, 30, 34));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Virtual GBA D-pad, A, B, L, R, Start and Select");
    }

    void releaseAll() {
        pointers.clear();
        publish();
    }

    private float radius() { return Math.max(24f, Math.min(getWidth(), getHeight()) * 0.072f); }

    private static int bit(int id) { return 1 << id; }

    private int buttonAt(float x, float y) {
        float w = getWidth(), h = getHeight(), r = radius();
        if (w <= 0 || h <= 0) return 0;
        float dx = (x - w * .25f) / (w * .11f);
        float dy = (y - h * .47f) / (h * .12f);
        if (Math.abs(dx) <= 1.7 && Math.abs(dy) <= 1.7) {
            int result = 0;
            if (dx < -.35f) result |= bit(LEFT);
            else if (dx > .35f) result |= bit(RIGHT);
            if (dy < -.35f) result |= bit(UP);
            else if (dy > .35f) result |= bit(DOWN);
            return result;
        }
        if (near(x, y, w * .80f, h * .43f, r * 1.4f)) return bit(A);
        if (near(x, y, w * .67f, h * .60f, r * 1.4f)) return bit(B);
        if (near(x, y, w * .16f, h * .14f, r * 1.4f)) return bit(L);
        if (near(x, y, w * .84f, h * .14f, r * 1.4f)) return bit(R);
        if (near(x, y, w * .40f, h * .84f, r * 1.2f)) return bit(SELECT);
        if (near(x, y, w * .60f, h * .84f, r * 1.2f)) return bit(START);
        return 0;
    }

    private boolean near(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    private void publish() {
        int next = pointers.mask();
        if (mask != next) {
            mask = next;
            listener.buttonsChanged(next);
            invalidate();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }
        int liftedIndex = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                ? event.getActionIndex() : -1;
        pointers.clear();
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == liftedIndex) continue;
            int id = event.getPointerId(i);
            int current = buttonAt(event.getX(i), event.getY(i));
            pointers.setPointer(id, current);
        }
        publish();
        if (action == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void circle(Canvas canvas, float x, float y, float r, String label, int key) {
        paint.setColor((mask & bit(key)) != 0 ? Color.rgb(176, 192, 228) : Color.rgb(76, 80, 88));
        canvas.drawCircle(x, y, r, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(r * .54f);
        canvas.drawText(label, x, y + r * .18f, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), r = radius();
        circle(canvas, w * .25f, h * .35f, r, "↑", UP);
        circle(canvas, w * .25f, h * .59f, r, "↓", DOWN);
        circle(canvas, w * .14f, h * .47f, r, "←", LEFT);
        circle(canvas, w * .36f, h * .47f, r, "→", RIGHT);
        circle(canvas, w * .80f, h * .43f, r, "A", A);
        circle(canvas, w * .67f, h * .60f, r, "B", B);
        circle(canvas, w * .16f, h * .14f, r * .90f, "L", L);
        circle(canvas, w * .84f, h * .14f, r * .90f, "R", R);
        circle(canvas, w * .40f, h * .84f, r * .75f, "SEL", SELECT);
        circle(canvas, w * .60f, h * .84f, r * .75f, "START", START);
    }
}
