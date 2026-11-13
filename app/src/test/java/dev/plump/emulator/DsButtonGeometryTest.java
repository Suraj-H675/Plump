package dev.plump.emulator;

import org.junit.Test;
import static org.junit.Assert.*;

public class DsButtonGeometryTest {
    private void check(boolean left, float x, float y, int key) {
        assertEquals(DsButtonGeometry.bit(key), DsButtonGeometry.hit(left, x, y));
    }

    @Test public void allTwelveDsButtonsHaveIndependentHitRegions() {
        check(true, .50f, .11f, DsButtonGeometry.L);
        check(true, .50f, .37f, DsButtonGeometry.UP);
        check(true, .25f, .49f, DsButtonGeometry.LEFT);
        check(true, .75f, .49f, DsButtonGeometry.RIGHT);
        check(true, .50f, .63f, DsButtonGeometry.DOWN);
        check(true, .50f, .89f, DsButtonGeometry.SELECT);
        check(false, .76f, .09f, DsButtonGeometry.R);
        check(false, .48f, .25f, DsButtonGeometry.X);
        check(false, .25f, .43f, DsButtonGeometry.Y);
        check(false, .74f, .48f, DsButtonGeometry.A);
        check(false, .47f, .68f, DsButtonGeometry.B);
        check(false, .52f, .88f, DsButtonGeometry.START);
    }

    @Test public void simultaneousDirectionalAndFaceButtonsCombine() {
        assertEquals(DsButtonGeometry.bit(DsButtonGeometry.UP) |
                        DsButtonGeometry.bit(DsButtonGeometry.LEFT),
                DsButtonGeometry.hit(true, .2f, .35f));
        ButtonInputState left = new ButtonInputState(), right = new ButtonInputState();
        left.setPointer(3, DsButtonGeometry.hit(true, .5f, .37f));
        right.setPointer(17, DsButtonGeometry.hit(false, .74f, .48f));
        right.setPointer(19, DsButtonGeometry.hit(false, .47f, .68f));
        assertEquals(DsButtonGeometry.bit(DsButtonGeometry.UP) |
                     DsButtonGeometry.bit(DsButtonGeometry.A) |
                     DsButtonGeometry.bit(DsButtonGeometry.B), left.mask() | right.mask());
        right.clear();
        assertEquals(DsButtonGeometry.bit(DsButtonGeometry.UP), left.mask() | right.mask());
    }

    @Test public void outsideAndTransparentZonesDoNotPressButtons() {
        assertEquals(0, DsButtonGeometry.hit(true, .5f, .25f));
        assertEquals(0, DsButtonGeometry.hit(false, 1, .5f));
        assertEquals(0, DsButtonGeometry.hit(false, -.1f, .3f));
        assertEquals(0, DsButtonGeometry.hit(true, Float.NaN, .4f));
    }
}
