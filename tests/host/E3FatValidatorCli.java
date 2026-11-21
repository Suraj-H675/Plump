package dev.plump.emulator;

import java.nio.file.Path;

/** Host CLI for testing the exact Android-compatible read-only FAT16 validator. */
public final class E3FatValidatorCli {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 && (args.length != 2 || !"--record".equals(args[1])))
            throw new IllegalArgumentException("Expected one disposable FAT16 image path, optional --record");
        Path image = Path.of(args[0]).toRealPath();
        new VirtualSdFat16Validator().validate(image);
        System.out.println("PASS_E3_ANDROID_FAT16_VALIDATOR " + image.getFileName());
        if (args.length == 2) {
            E3DiagnosticFatRecord.Record record = E3DiagnosticFatRecord.read(image, true);
            System.out.println("PASS_E3_ANDROID_DLDI_RECORD counter=" + record.counter +
                    " previous=" + record.previous + " present=" + record.present);
        }
    }
}
