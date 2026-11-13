package dev.plump.emulator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** DS-only left/right side controls; kept separate from the stylus surface. */
final class DsControlsView extends View {
    interface Listener { void buttonsChanged(); }
    private static final int A = DsButtonGeometry.A, B = DsButtonGeometry.B;
    private static final int X = DsButtonGeometry.X, Y = DsButtonGeometry.Y;
    private static final int L = DsButtonGeometry.L, R = DsButtonGeometry.R;
    private static final int UP = DsButtonGeometry.UP, DOWN = DsButtonGeometry.DOWN;
    private static final int LEFT = DsButtonGeometry.LEFT, RIGHT = DsButtonGeometry.RIGHT;
    private static final int START = DsButtonGeometry.START, SELECT = DsButtonGeometry.SELECT;
    private final boolean leftSide;
    private final Listener listener;
    private final ButtonInputState pointers = new ButtonInputState();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mask;

    DsControlsView(Context context, boolean leftSide, Listener listener) {
        super(context);
        this.leftSide = leftSide;
        this.listener = listener;
        setBackgroundColor(Color.rgb(27, 30, 34));
        setContentDescription(leftSide ? "DS D-pad, L, Select" : "DS A, B, X, Y, R, Start");
    }

    private static int bit(int id) { return DsButtonGeometry.bit(id); }
    int mask() { return mask; }

    void releaseAll() {
        pointers.clear();
        publish();
    }

    private int hit(float x, float y) {
        float w = getWidth(), h = getHeight();
        return w <= 0 || h <= 0 ? 0 : DsButtonGeometry.hit(leftSide, x / w, y / h);
    }

    private void publish() {
        int value = pointers.mask();
        if (value == mask) return;
        mask = value;
        invalidate();
        listener.buttonsChanged();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) { releaseAll(); return true; }
        int lifted = action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
                ? event.getActionIndex() : -1;
        pointers.clear();
        for (int i = 0; i < event.getPointerCount(); ++i) {
            if (i == lifted) continue;
            pointers.setPointer(event.getPointerId(i), hit(event.getX(i), event.getY(i)));
        }
        publish();
        if (action == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private void button(Canvas canvas, String label, int id, float x, float y) {
        float r = Math.max(18, Math.min(getWidth() * .17f, getHeight() * .095f));
        paint.setColor((mask & bit(id)) != 0 ? Color.rgb(165, 185, 219) : Color.rgb(76, 81, 88));
        canvas.drawCircle(x * getWidth(), y * getHeight(), r, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(r * .58f);
        canvas.drawText(label, x * getWidth(), y * getHeight() + r * .19f, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (leftSide) {
            button(canvas, "L", L, .50f, .11f);
            button(canvas, "↑", UP, .50f, .37f);
            button(canvas, "←", LEFT, .25f, .49f);
            button(canvas, "→", RIGHT, .75f, .49f);
            button(canvas, "↓", DOWN, .50f, .63f);
            button(canvas, "SEL", SELECT, .50f, .89f);
        } else {
            button(canvas, "R", R, .76f, .09f);
            button(canvas, "X", X, .48f, .25f);
            button(canvas, "Y", Y, .25f, .43f);
            button(canvas, "A", A, .74f, .48f);
            button(canvas, "B", B, .47f, .68f);
            button(canvas, "START", START, .52f, .88f);
        }
    }
}
