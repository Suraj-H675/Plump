// SPDX-License-Identifier: CC0-1.0
// Original Plump test code using public, CC0 BlocksDS headers and examples.
// Tests the Nintendo DS AUX SPI EEPROM path with a SYNTHETIC cartridge header.
// No commercial game code, firmware or user-owned save data is involved.

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <nds.h>

#define SAVE_ADDRESS 0x180
#define SAVE_LEN 16

static bool matches(const uint8_t* value)
{
    static const uint8_t magic[] = {'P','L','M','P','-','D','S','3'};
    if (memcmp(value, magic, sizeof(magic)) != 0)
        return false;
    return value[9] == (uint8_t)~value[8];
}

int main(void)
{
    consoleDemoInit();
    lcdSwap(); // Print to physical upper DS panel.

    // Slot-1 is shared between ARM7 and ARM9; we must explicitly request the
    // ARM9 bus owner before reading/writing the emulated cartridge EEPROM.
    sysSetCardOwner(BUS_OWNER_ARM9);

    uint8_t previous[SAVE_LEN] = {0};
    cardReadEeprom(SAVE_ADDRESS, previous, SAVE_LEN, 2);
    const bool restored = matches(previous);
    uint8_t counter = restored ? previous[8] : 0;
    bool wrote = false;
    bool verified = false;

    for (;;) {
        swiWaitForVBlank();
        scanKeys();
        uint16_t pressed = keysDown();
        uint16_t held = keysHeld();

        if ((held & (KEY_START | KEY_SELECT)) == (KEY_START | KEY_SELECT))
            break;

        if (pressed & KEY_A) {
            uint8_t candidate[SAVE_LEN] = {
                'P','L','M','P','-','D','S','3',
                (uint8_t)(counter + 1), (uint8_t)~(counter + 1),
                0xAC, 0x53, 0x01, 0x02, 0x03, 0x04
            };
            cardWriteEeprom(SAVE_ADDRESS, candidate, SAVE_LEN, 2);
            uint8_t check[SAVE_LEN] = {0};
            cardReadEeprom(SAVE_ADDRESS, check, SAVE_LEN, 2);
            verified = (memcmp(candidate, check, SAVE_LEN) == 0);
            wrote = true;
            if (verified)
                counter = candidate[8];
        }

        consoleClear();
        printf("PLUMP DS EEPROM DIAGNOSTIC\n\n");
        printf("Initial save: %s\n", restored ? "RESTORED" : "NEW");
        printf("Initial counter: %u\n", restored ? previous[8] : 0);
        printf("Current counter: %u\n", counter);
        printf("Write: %s\n", wrote ? (verified ? "VERIFIED" : "FAILED") : "NOT REQUESTED");
        printf("\nPress A to write EEPROM\n");
        printf("Pause and force-stop Plump\n");
        printf("then re-open this game.\n");
        printf("START+SELECT = EXIT\n");
    }
    return 0;
}
