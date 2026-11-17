// SPDX-License-Identifier: CC0-1.0
//
// SPDX-FileContributor: Antonio Niño Díaz, 2023-2025 (upstream example basis)
// SPDX-FileContributor: Plump E1-B diagnostic adaptation, 2026
//
// Original Plump diagnostic adapted from the BlocksDS
// examples/filesystem/save_data CC0-1.0 example. This is deliberately a tiny
// ARM9 program: it uses libfat's real DLDI-backed FAT interface and does not
// emulate or substitute filesystem writes on the host.

#include <errno.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#include <fat.h>
#include <nds.h>
#include <nds/arm9/dldi.h>

#ifndef PLUMP_ROM_ID
#error "PLUMP_ROM_ID must identify this diagnostic ROM"
#endif

#define RECORD_MAGIC 0x4D554C50u /* bytes "PLUM" on the DS little-endian ARM9 */
#define RECORD_VERSION 1u

typedef struct
{
    uint32_t magic;
    uint32_t version;
    uint32_t identity;
    uint32_t counter;
    uint32_t checksum;
    uint32_t checksum_inverse;
} record_t;

#if PLUMP_ROM_ID == 0xA1A10001
#define ROM_LABEL "ALPHA"
#define STATE_SUFFIX "PLUMP/A001.BIN"
#define REPORT_SUFFIX "PLUMP/A001.TXT"
#elif PLUMP_ROM_ID == 0xB2B20002
#define ROM_LABEL "BETA"
#define STATE_SUFFIX "PLUMP/B002.BIN"
#define REPORT_SUFFIX "PLUMP/B002.TXT"
#else
#error "Unknown Plump E1-B ROM identity"
#endif

static PrintConsole top_screen;
static char directory_path[32];
static char state_path[64];
static char report_path[64];

static uint32_t record_checksum(const record_t *record)
{
    const uint8_t *bytes = (const uint8_t *)record;
    uint32_t hash = 2166136261u;

    // Hash the fixed first four words, excluding the checksum fields.
    for (size_t i = 0; i < 4u * sizeof(uint32_t); ++i)
    {
        hash ^= bytes[i];
        hash *= 16777619u;
    }

    return hash;
}

static bool record_is_valid(const record_t *record)
{
    return record->magic == RECORD_MAGIC &&
           record->version == RECORD_VERSION &&
           record->identity == (uint32_t)PLUMP_ROM_ID &&
           record->counter != 0 &&
           record->checksum == record_checksum(record) &&
           record->checksum_inverse == ~record->checksum;
}

// 0 = missing, 1 = valid record, -1 = I/O error, -2 = invalid record.
static int read_record(record_t *record)
{
    errno = 0;
    FILE *file = fopen(state_path, "rb");
    if (file == NULL)
        return errno == ENOENT ? 0 : -1;

    const size_t bytes_read = fread(record, 1, sizeof(*record), file);
    const int trailing_byte = fgetc(file);
    const bool read_error = ferror(file) != 0;
    const int close_result = fclose(file);

    if (bytes_read != sizeof(*record) || trailing_byte != EOF ||
        read_error || close_result != 0)
        return -2;

    return record_is_valid(record) ? 1 : -2;
}

static bool write_record(const record_t *record)
{
    FILE *file = fopen(state_path, "wb");
    if (file == NULL)
        return false;

    const size_t bytes_written = fwrite(record, 1, sizeof(*record), file);
    const int flush_result = fflush(file);
    const bool write_error = ferror(file) != 0;
    const int close_result = fclose(file);

    return bytes_written == sizeof(*record) && flush_result == 0 &&
           !write_error && close_result == 0;
}

static bool write_and_verify_report(const char *result, const char *error,
                                    uint32_t previous, const record_t *record)
{
    char expected[256];
    const int length = snprintf(
        expected, sizeof(expected),
        "PLUMP_E1B_RESULT=%s\n"
        "ROM=%s\n"
        "ROM_ID=%08" PRIX32 "\n"
        "PREVIOUS=%" PRIu32 "\n"
        "COUNTER=%" PRIu32 "\n"
        "CHECKSUM=%08" PRIX32 "\n"
        "RECORD_BYTES=%u\n"
        "ERROR=%s\n",
        result, ROM_LABEL, (uint32_t)PLUMP_ROM_ID, previous,
        record ? record->counter : 0u,
        record ? record->checksum : 0u,
        (unsigned)sizeof(record_t), error ? error : "NONE");

    if (length < 0 || (size_t)length >= sizeof(expected))
        return false;

    FILE *file = fopen(report_path, "wb");
    if (file == NULL)
        return false;

    const size_t bytes_written = fwrite(expected, 1, (size_t)length, file);
    const int flush_result = fflush(file);
    const bool write_error = ferror(file) != 0;
    const int close_result = fclose(file);
    if (bytes_written != (size_t)length || flush_result != 0 ||
        write_error || close_result != 0)
        return false;

    // Independently reopen the game-written result file and compare every byte.
    file = fopen(report_path, "rb");
    if (file == NULL)
        return false;

    char actual[sizeof(expected)];
    const size_t actual_length = fread(actual, 1, sizeof(actual), file);
    const int trailing_byte = fgetc(file);
    const bool read_error = ferror(file) != 0;
    const int reopen_close_result = fclose(file);

    return actual_length == (size_t)length && trailing_byte == EOF &&
           !read_error && reopen_close_result == 0 &&
           memcmp(actual, expected, (size_t)length) == 0;
}

static void show_result(const char *status, uint32_t previous,
                        const record_t *record, const char *detail)
{
    consoleSelect(&top_screen);
    consoleClear();

    printf("PLUMP E1-B DLDI\n");
    printf("ROM: %s\n", ROM_LABEL);
    printf("ID: %08" PRIX32 "\n", (uint32_t)PLUMP_ROM_ID);
    printf("DLDI: %s\n\n", io_dldi_data->friendlyName);
    printf("RESULT: %s\n", status);
    printf("PREVIOUS: %" PRIu32 "\n", previous);
    if (record != NULL)
    {
        printf("COUNTER: %" PRIu32 "\n", record->counter);
        printf("CHECKSUM: %08" PRIX32 "\n", record->checksum);
    }
    if (detail != NULL)
        printf("DETAIL: %s\n", detail);
}

static void wait_for_unload(void)
{
    for (;;)
        swiWaitForVBlank();
}

int main(void)
{
    videoSetMode(MODE_0_2D);
    videoSetModeSub(MODE_0_2D);
    vramSetBankA(VRAM_A_MAIN_BG);
    vramSetBankC(VRAM_C_SUB_BG);
    consoleInit(&top_screen, 3, BgType_Text4bpp, BgSize_T_256x256,
                31, 0, true, true);
    consoleSelect(&top_screen);

    printf("PLUMP E1-B DLDI WRITER\n");
    printf("ROM: %s (%08" PRIX32 ")\n", ROM_LABEL,
           (uint32_t)PLUMP_ROM_ID);
    printf("DLDI: %s\n", io_dldi_data->friendlyName);
    printf("Initializing FAT...\n");

    if (!fatInitDefault())
    {
        show_result("FAT_INIT_FAIL", 0, NULL, "DLDI/FAT unavailable");
        wait_for_unload();
    }

    // DS mode must expose the DLDI-backed default drive, not DSi SD or NAND.
    const char *drive = fatGetDefaultDrive();
    if (drive == NULL || strcmp(drive, "fat:/") != 0)
    {
        show_result("NOT_DLDI", 0, NULL, "default drive is not fat:/");
        wait_for_unload();
    }

    if (snprintf(directory_path, sizeof(directory_path), "%sPLUMP", drive) < 0 ||
        snprintf(state_path, sizeof(state_path), "%s", drive) < 0 ||
        snprintf(report_path, sizeof(report_path), "%s", drive) < 0)
    {
        show_result("PATH_FAIL", 0, NULL, "could not construct FAT paths");
        wait_for_unload();
    }

    // Append identity-specific relative paths to the verified fat:/ drive.
    strncat(state_path, STATE_SUFFIX, sizeof(state_path) - strlen(state_path) - 1);
    strncat(report_path, REPORT_SUFFIX, sizeof(report_path) - strlen(report_path) - 1);

    if (mkdir(directory_path, 0777) != 0 && errno != EEXIST)
    {
        show_result("DIRECTORY_FAIL", 0, NULL, "mkdir /PLUMP failed");
        wait_for_unload();
    }

    record_t previous_record = {0};
    const int read_result = read_record(&previous_record);
    if (read_result == -1)
    {
        (void)write_and_verify_report("ERROR", "STATE_READ_IO", 0, NULL);
        show_result("STATE_READ_FAIL", 0, NULL, "existing record I/O error");
        wait_for_unload();
    }
    if (read_result == -2)
    {
        (void)write_and_verify_report("ERROR", "BAD_RECORD", 0, NULL);
        show_result("BAD_RECORD", 0, NULL, "record rejected; not overwritten");
        wait_for_unload();
    }

    const uint32_t previous = read_result == 1 ? previous_record.counter : 0u;
    if (previous == UINT32_MAX)
    {
        (void)write_and_verify_report("ERROR", "COUNTER_OVERFLOW", previous,
                                      &previous_record);
        show_result("COUNTER_OVERFLOW", previous, &previous_record, NULL);
        wait_for_unload();
    }

    record_t next = {
        .magic = RECORD_MAGIC,
        .version = RECORD_VERSION,
        .identity = (uint32_t)PLUMP_ROM_ID,
        .counter = previous + 1u,
        .checksum = 0,
        .checksum_inverse = 0,
    };
    next.checksum = record_checksum(&next);
    next.checksum_inverse = ~next.checksum;

    if (!write_record(&next))
    {
        show_result("STATE_WRITE_FAIL", previous, &next,
                    "fopen/fwrite/fflush/fclose failed");
        wait_for_unload();
    }

    record_t reopened = {0};
    if (read_record(&reopened) != 1 ||
        memcmp(&reopened, &next, sizeof(next)) != 0)
    {
        (void)write_and_verify_report("ERROR", "STATE_READBACK_MISMATCH",
                                      previous, &next);
        show_result("READBACK_FAIL", previous, &next,
                    "closed file did not verify after reopen");
        wait_for_unload();
    }

    if (!write_and_verify_report("PASS", "NONE", previous, &reopened))
    {
        show_result("REPORT_WRITE_FAIL", previous, &reopened,
                    "state verified; machine-readable report failed");
        wait_for_unload();
    }

    show_result("PASS", previous, &reopened,
                "write, flush, close, reopen, checksum verified");
    wait_for_unload();
    return 0;
}
