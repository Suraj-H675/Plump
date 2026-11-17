package dev.plump.emulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** D4 genuine PLSV/selector host transaction interrupts, each killed in a separate JVM. */
public final class PromotionD4HardKillProbe {
    private static final int EXIT_CODE = 87;

    private static void kill(String point) {
        System.out.println("HARD_KILL_87 " + point);
        System.out.flush();
        Runtime.getRuntime().halt(EXIT_CODE);
    }

    private static boolean rollbackStage(String point) {
        return point.startsWith("ROLLBACK_");
    }

    private static void killTransaction(Path root, String console, String point) throws Exception {
        final boolean[] inRollback = {false};
        NativeSavePromotionStore.FaultInjector promotionFault = fault -> {
            // The ROLLBACK_ prefix distinguishes the test phase from the actual
            // enum name, which already includes the rollback boundary itself.
            String actualPoint = rollbackStage(point) ? point.substring("ROLLBACK_".length()) : point;
            if (fault.name().equals(actualPoint) ||
                    ("RECOVER_FINISH_AFTER_PLSV_WRITE".equals(point) && inRollback[0] &&
                            fault == NativeSavePromotionStore.FaultPoint.AFTER_PLSV_WRITE) ||
                    ("RECOVER_ROLLBACK_AFTER_WRITE".equals(point) && inRollback[0] &&
                            fault == NativeSavePromotionStore.FaultPoint.AFTER_ROLLBACK_WRITE))
                kill(point);
            if ((point.startsWith("RECOVER_") && !inRollback[0]) &&
                    fault == NativeSavePromotionStore.FaultPoint.AFTER_INTENT)
                throw new IOException("Prepare pending promotion for explicit recovery test");
        };
        GameSaveStore.FailureInjector saveFault = fault -> {
            if ((point.equals("DURING_PLSV_PARTIAL_WRITE") ||
                    point.equals("AFTER_PLSV_RECORD_MOVE")) && !inRollback[0] &&
                    fault.name().equals(point.equals("DURING_PLSV_PARTIAL_WRITE") ?
                            "RECORD_PARTIAL_WRITE" : "AFTER_RECORD_MOVE")) kill(point);
            if ((point.equals("ROLLBACK_DURING_PLSV_PARTIAL_WRITE") ||
                    point.equals("ROLLBACK_AFTER_PLSV_RECORD_MOVE")) && inRollback[0] &&
                    fault.name().equals(point.equals("ROLLBACK_DURING_PLSV_PARTIAL_WRITE") ?
                            "RECORD_PARTIAL_WRITE" : "AFTER_RECORD_MOVE")) kill(point);
        };
        PromotionD4HostCli.Stores s = new PromotionD4HostCli.Stores(root, console,
                promotionFault, saveFault);
        NativeSavePromotionStore.Approval approved;
        try (PromotionD4HostCli.Owned host = new PromotionD4HostCli.Owned(s)) {
            NativeSavePromotionStore.Proposal proposal = host.owner.proposeNativeSavePromotion(host.token);
            approved = host.owner.approveNativeSavePromotion(host.token, proposal,
                    proposal.confirmationText);
            Files.writeString(root.resolve("transaction-id.txt"), approved.transactionId,
                    StandardOpenOption.CREATE_NEW);
            try {
                NativeSavePromotionStore.Result result = host.owner.promoteNativeSaveBranch(host.token,
                        approved);
                if (result.classification != NativeSavePromotionStore.Classification.COMMITTED)
                    throw new AssertionError("Unexpected promotion result " + result.classification);
            } catch (IOException expected) {
                if (!point.startsWith("RECOVER_")) throw expected;
                if (!expected.getMessage().contains("pending promotion")) throw expected;
            }
        }
        if (point.startsWith("RECOVER_")) {
            inRollback[0] = true; // Mark second phase of this process as the recovery attempt.
            PromotionD4HostCli.Stores recovery = new PromotionD4HostCli.Stores(root, console,
                    promotionFault, saveFault);
            if (point.equals("RECOVER_FINISH_AFTER_PLSV_WRITE"))
                recovery.timeline.promotions().recoverFinish(recovery.id, recovery.canonical,
                        recovery.branches, approved.transactionId);
            else if (point.equals("RECOVER_ROLLBACK_AFTER_WRITE"))
                recovery.timeline.promotions().recoverRollback(recovery.id, recovery.canonical,
                        recovery.branches, approved.transactionId);
            else throw new IllegalArgumentException(point);
            throw new AssertionError("Expected explicit recovery process death");
        }
        if (rollbackStage(point)) {
            inRollback[0] = true;
            s.timeline.promotions().rollback(s.id, s.canonical, s.branches,
                    approved.transactionId);
            throw new AssertionError("Expected rollback process death");
        }
        throw new AssertionError("Expected promotion process death at " + point);
    }

    private static void verifyInterrupted(Path root, String console, String point, Path source)
            throws Exception {
        PromotionD4HostCli.Stores stores = new PromotionD4HostCli.Stores(root, console,
                NativeSavePromotionStore.NO_FAULTS, ignored -> { });
        PromotionD4HostCli.assertBranch(stores, source);
        NativeSavePromotionStore.RecoveryInfo status = stores.timeline.promotions().inspect(stores.id);
        boolean terminalPromoted = point.equals("AFTER_FINAL_COMMIT");
        boolean terminalRollback = point.equals("ROLLBACK_AFTER_ROLLED_BACK_RECEIPT");
        boolean beforeAnchor = point.equals("BEFORE_ANCHOR");
        NativeSavePromotionStore.Classification wanted = terminalPromoted ?
                NativeSavePromotionStore.Classification.COMMITTED : terminalRollback ?
                NativeSavePromotionStore.Classification.ROLLED_BACK :
                NativeSavePromotionStore.Classification.RECOVERY_REQUIRED;
        if (status.classification != wanted)
            throw new AssertionError("Incorrect deterministic restart status after " + point + ": " +
                    status.classification + " expected " + wanted + ": " + status.detail);
        Path anchor = root.resolve("active-timelines/native-save-promotions/" +
                stores.id.console + "-" + stores.id.romSha256 + "/" +
                stores.transaction() + "/anchor");
        if (beforeAnchor) {
            if (Files.exists(anchor))
                throw new AssertionError("An anchor appeared before the anchor publication boundary");
            if (!Files.readString(root.resolve("canonical-before.txt")).equals(
                    PromotionD4HostCli.hashes(root.resolve("canonical-plsv")).toString()))
                throw new AssertionError("Canonical progress changed before any verified anchor");
        } else {
            PromotionD4HostCli.assertAnchor(stores, source);
        }
        ActiveTimelineStore.Resolution result = stores.timeline.resolveCold(true, stores.id,
                stores.canonical, stores.branches);
        if (terminalPromoted || terminalRollback) {
            if (result.classification != ActiveTimelineStore.Classification.CANONICAL)
                throw new AssertionError("Committed canonical selector cannot cold recover: " + result.classification);
            byte[] expected = Files.readAllBytes(source.resolve(terminalPromoted ?
                    "n_branch.bin" : "n_latest.bin"));
            if (!Arrays.equals(result.nativeSave, expected))
                throw new AssertionError("Terminal native progress mismatches expected verified timeline");
        } else if (result.classification != ActiveTimelineStore.Classification.RECOVERY_REQUIRED) {
            throw new AssertionError("An incomplete promotion became automatically runnable: " +
                    point + " => " + result.classification);
        }
        // The originally promoted source branch and immutable S_state+N_state pair must survive
        // every possible cut, including after an individual canonical slot was replaced.
        System.out.println("PASS D4 FRESH-JVM HARD-KILL RECOVERY " + console + " " + point +
                " => " + status.classification + "; source branch unchanged; " +
                (beforeAnchor ? "pre-anchor canonical 3 files unchanged" :
                        "original canonical 3 files SHA/size fully recoverable from immutable anchor"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException(
                "prepare|kill|verify ROOT gba|ds POINT SOURCE_DIR");
        String action = args[0];
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        String console = args[2];
        String point = args[3];
        Path source = Path.of(args[4]).toAbsolutePath().normalize();
        switch (action) {
            case "prepare":
                PromotionD4HostCli.seed(new PromotionD4HostCli.Stores(root, console,
                        NativeSavePromotionStore.NO_FAULTS, ignored -> { }), source);
                break;
            case "kill": killTransaction(root, console, point); break;
            case "verify": verifyInterrupted(root, console, point, source); break;
            default: throw new IllegalArgumentException(action);
        }
    }
}
