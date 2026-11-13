/* Original Plump P2 diagnostic GBA ROM, compiled for ARM7TDMI.
 * This is a real GBA cartridge program, not a simulated emulator screen.
 * Keypad bit 0=A, 1=B, 2=Select, 3=Start, 4=Right, 5=Left,
 * 6=Up, 7=Down, 8=R, 9=L. Color fills are GBA BGR555.
 * A also unmutes hardware PSG channel 1, producing a test tone.
 * MIT-licensed gba-tests contributes only the standard cartridge header,
 * verified and added by scripts/build-p2-input-audio-rom.sh.
 */
    .syntax unified
    .cpu arm7tdmi
    .arm
    .section .text,"ax",%progbits
    .balign 4
    .space 192                  /* standard GBA ROM header lives here */
    .global _start
_start:
    ldr r0, =0x04000000       /* DISPCNT */
    ldr r2, =0x0403           /* bitmap BG2, mode 3 */
    strh r2, [r0]

    ldr r0, =0x04000084       /* SOUNDCNT_X: PSG master enable */
    mov r2, #0x80
    strh r2, [r0]
    ldr r0, =0x04000082       /* SOUNDCNT_H: PSG ratio */
    mov r2, #2
    strh r2, [r0]
    ldr r0, =0x04000062       /* SOUND1CNT_H: 50% duty, full envelope */
    ldr r2, =0xf080
    strh r2, [r0]
    ldr r0, =0x04000064       /* SOUND1CNT_X: trigger ~468-Hz tone */
    ldr r2, =0x86e8
    strh r2, [r0]

    mvn r10, #0              /* force first screen draw */
poll:
    ldr r0, =0x04000130       /* KEYINPUT (active low) */
    ldrh r1, [r0]
    mvn r1, r1
    ldr r2, =0x03ff
    and r1, r1, r2
    cmp r1, r10
    beq poll
    mov r10, r1

    /* Hardware-generated tone is audible only while A is held. */
    ldr r0, =0x04000080       /* SOUNDCNT_L: speaker routing */
    mov r2, #0
    tst r1, #1
    ldrne r2, =0x1177       /* enable channel 1 left + right */
    strh r2, [r0]

    /* One unambiguous color for every individual button. */
    ldr r3, =0x0842          /* no buttons: dark gray */
    tst r1, #1
    ldrne r3, =0x001f       /* A: red */
    tst r1, #2
    ldrne r3, =0x7c00       /* B: blue */
    tst r1, #4
    ldrne r3, =0x4210       /* SELECT: gray */
    tst r1, #8
    ldrne r3, =0x7c1f       /* START: magenta */
    tst r1, #16
    ldrne r3, =0x401f       /* RIGHT: purple */
    tst r1, #32
    ldrne r3, =0x01ff       /* LEFT: orange/yellow-ish */
    tst r1, #64
    ldrne r3, =0x03e0       /* UP: green */
    tst r1, #128
    ldrne r3, =0x7fe0       /* DOWN: cyan */
    tst r1, #256
    ldrne r3, =0x7fff       /* R: white */
    tst r1, #512
    ldrne r3, =0x03ff       /* L: yellow */
    and r2, r1, #3
    cmp r2, #3
    ldreq r3, =0x03ff       /* A+B together: yellow */

    /* Fill the actual 240x160 framebuffer in GBA mode 3. */
    ldr r4, =0x06000000
    orr r5, r3, r3, lsl #16
    ldr r6, =19200          /* 38400 pixels, two per word */
fill:
    str r5, [r4], #4
    subs r6, r6, #1
    bne fill
    b poll

    .ltorg
