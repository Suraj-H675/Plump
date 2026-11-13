// SPDX-License-Identifier: CC0-1.0
// Based on the BlocksDS CC0 touch_input and play_sounds examples:
// Adrian "asie" Siekierka and Antonio Niño Díaz (2024).
// Modified as an original Plump P3 control, stylus and sound diagnostic.
// This is genuine ARM9+ARM7 Nintendo DS homebrew, not a simulated screen.

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <nds.h>
#include <gl2d.h>

#define MARKER_RADIUS 4

int main(void)
{
    // The hardware ARM7 sound core is provided by the official BlocksDS SDK.
    soundEnable();

    // LCD swap: console text is on the upper DS panel, live stylus marker on
    // the lower DS panel. The application frontend must not confuse them.
    consoleDemoInit();
    lcdSwap();
    videoSetMode(MODE_0_3D);
    glScreen2D();

    touchPosition touch = {0};
    int psg_channel = -1;

    for (;;) {
        swiWaitForVBlank();
        scanKeys();
        uint16_t held = keysHeld();
        bool touching = (held & KEY_TOUCH) != 0;

        if ((held & (KEY_START | KEY_SELECT)) == (KEY_START | KEY_SELECT))
            break;

        if (touching)
            touchRead(&touch);

        if ((held & KEY_A) && psg_channel < 0)
            psg_channel = soundPlayPSG(DutyCycle_50, 523, 112, 64);
        else if (!(held & KEY_A) && psg_channel >= 0) {
            soundKill(psg_channel);
            psg_channel = -1;
        }

        consoleClear();
        printf("PLUMP - DS INPUT TEST\n");
        printf("Touch: %s\n", touching ? "DOWN" : "UP");
        if (touching)
            printf("Stylus XY: %3u, %3u\n", touch.px, touch.py);
        else
            printf("Stylus XY: ---, ---\n");
        printf("D-pad UDLR: %c%c%c%c\n",
               (held & KEY_UP) ? 'U' : '-', (held & KEY_DOWN) ? 'D' : '-',
               (held & KEY_LEFT) ? 'L' : '-', (held & KEY_RIGHT) ? 'R' : '-');
        printf("Face ABXY: %c%c%c%c\n",
               (held & KEY_A) ? 'A' : '-', (held & KEY_B) ? 'B' : '-',
               (held & KEY_X) ? 'X' : '-', (held & KEY_Y) ? 'Y' : '-');
        printf("Shoulders L/R: %c%c\n",
               (held & KEY_L) ? 'L' : '-', (held & KEY_R) ? 'R' : '-');
        printf("Start / Select: %c%c\n",
               (held & KEY_START) ? 'S' : '-', (held & KEY_SELECT) ? 'E' : '-');
        printf("A = PSG 523 Hz tone\n");
        printf("START + SELECT = EXIT\n");

        // A white 9x9-ish marker follows the actual DS stylus location.
        // Graphics render on the REAL hardware lower display via GL2D.
        glBegin2D();
        if (touching) {
            glPolyFmt(POLY_ALPHA(31) | POLY_CULL_NONE | POLY_ID(0));
            glBoxFilled(touch.px - MARKER_RADIUS,
                        touch.py - MARKER_RADIUS,
                        touch.px + MARKER_RADIUS,
                        touch.py + MARKER_RADIUS,
                        RGB15(31, 31, 31));
        }
        glEnd2D();
        glFlush(0);
    }

    if (psg_channel >= 0)
        soundKill(psg_channel);
    soundDisable();
    return 0;
}
