/* Original real ARM7TDMI GBA SRAM counter diagnostic, derived from no third-party code.
 * MIT gba-tests contributes only validated standard cartridge header at packaging.
 * Hardware KEYINPUT A rising edge writes SRAM[0x180] and its complement.
 */
.syntax unified
.cpu arm7tdmi
.arm
.section .text,"ax",%progbits
.balign 4
.space 192
.global _start
_start:
    ldr r7, =0x0e000180
    ldrb r5, [r7]
    cmp r5, #255
    moveq r5, #0
    mov r10, #0
poll:
    ldr r0, =0x04000130
    ldrh r1, [r0]
    tst r1, #1
    moveq r2, #1
    movne r2, #0
    cmp r2, r10
    beq poll
    mov r10, r2
    cmp r2, #0
    beq poll
    add r5, r5, #1
    strb r5, [r7]
    mvn r6, r5
    strb r6, [r7, #1]
    b poll
.ltorg
.asciz "SRAM_V113"
