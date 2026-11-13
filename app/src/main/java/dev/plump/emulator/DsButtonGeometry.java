package dev.plump.emulator;

/** Hit-test pure normalized coordinates, so controls and simultaneous input can be tested on JVM. */
final class DsButtonGeometry {
    static final int B = 0, Y = 1, SELECT = 2, START = 3;
    static final int UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7;
    static final int A = 8, X = 9, L = 10, R = 11;

    private DsButtonGeometry() {}

    static int bit(int id) { return 1 << id; }

    static int hit(boolean leftSide, float nx, float ny) {
        if (Float.isNaN(nx) || Float.isNaN(ny) || nx < 0 || nx >= 1 || ny < 0 || ny >= 1)
            return 0;
        if (leftSide) {
            if (ny > .28f && ny < .72f) {
                int mask = 0;
                if (nx < .34f) mask |= bit(LEFT);
                if (nx > .66f) mask |= bit(RIGHT);
                if (ny < .39f) mask |= bit(UP);
                if (ny > .61f) mask |= bit(DOWN);
                if (mask != 0) return mask;
            }
            if (ny < .22f) return bit(L);
            if (ny > .78f) return bit(SELECT);
        } else {
            if (ny < .19f && nx > .62f) return bit(R);
            if (ny > .79f) return bit(START);
            if (ny < .34f) return bit(X);
            if (ny < .48f && nx < .48f) return bit(Y);
            if (ny < .59f && nx >= .48f) return bit(A);
            if (ny < .79f) return bit(B);
        }
        return 0;
    }
}
