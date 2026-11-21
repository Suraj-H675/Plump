package dev.plump.emulator;

import org.junit.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class E3DiagnosticRunCoordinatorTest {
    @Test public void cancellationAtWorkerBoundariesKeepsAdmissionUntilCleanupCompletes() throws Exception {
        for (E3DiagnosticRunCoordinator.Phase phase : new E3DiagnosticRunCoordinator.Phase[]{
                E3DiagnosticRunCoordinator.Phase.PREFLIGHT,
                E3DiagnosticRunCoordinator.Phase.SEED,
                E3DiagnosticRunCoordinator.Phase.BASELINE,
                E3DiagnosticRunCoordinator.Phase.WORKING_COPY,
                E3DiagnosticRunCoordinator.Phase.CORE_INITIALIZATION,
                E3DiagnosticRunCoordinator.Phase.GAME_LOAD,
                E3DiagnosticRunCoordinator.Phase.FRAMES,
                E3DiagnosticRunCoordinator.Phase.COMMIT}) {
            E3DiagnosticRunCoordinator coordinator = new E3DiagnosticRunCoordinator();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch continueWorker = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<VirtualSdGenerationStore.Outcome> outcome = new AtomicReference<>();
            E3DiagnosticRunCoordinator.StartResult started = coordinator.start(control -> {
                try {
                    control.checkpoint(phase);
                    entered.countDown();
                    await(continueWorker);
                    control.checkCancelled();
                    outcome.set(VirtualSdGenerationStore.Outcome.NOT_COMMITTED);
                    return result(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);
                } catch (IOException cancelled) {
                    control.enterCleanup();
                    outcome.set(VirtualSdGenerationStore.Outcome.NOT_COMMITTED);
                    return result(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(started.started);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            started.handle.requestCancel();
            E3DiagnosticRunCoordinator.StartResult duplicate = coordinator.start(control -> {
                fail("a recreated activity must not start an overlapping native owner");
                return result(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);
            });
            assertFalse(duplicate.started);
            assertEquals(started.handle.id(), duplicate.handle.id());
            continueWorker.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            awaitTerminal(started.handle);
            assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, outcome.get());
            assertEquals(E3DiagnosticRunCoordinator.Phase.FINISHED, started.handle.snapshot().phase);
            assertNull(coordinator.activeRun());
        }
    }

    @Test public void cancellationBeforePublicationAuthorizationWinsTheRenameRace() throws Exception {
        E3DiagnosticRunCoordinator coordinator = new E3DiagnosticRunCoordinator();
        CountDownLatch beforeAuthorization = new CountDownLatch(1);
        CountDownLatch authorize = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Boolean> published = new AtomicReference<>(false);
        E3DiagnosticRunCoordinator.StartResult started = coordinator.start(control -> {
            control.checkpoint(E3DiagnosticRunCoordinator.Phase.COMMIT);
            beforeAuthorization.countDown();
            await(authorize);
            String candidate = UUID.randomUUID().toString();
            boolean permitted = control.authorizePublication(candidate);
            published.set(permitted);
            if (permitted) control.publicationResolved(candidate, VirtualSdGenerationStore.Outcome.COMMITTED);
            finished.countDown();
            return result(permitted ? VirtualSdGenerationStore.Outcome.COMMITTED :
                    VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);
        });
        assertTrue(beforeAuthorization.await(2, TimeUnit.SECONDS));
        started.handle.requestCancel();
        authorize.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        awaitTerminal(started.handle);
        assertFalse(published.get());
        assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED,
                started.handle.snapshot().result.outcome);
    }

    @Test public void cancellationAfterAuthorizationIsDeferredUntilExactResultResolves() throws Exception {
        E3DiagnosticRunCoordinator coordinator = new E3DiagnosticRunCoordinator();
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch resolve = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        String candidate = UUID.randomUUID().toString();
        E3DiagnosticRunCoordinator.StartResult started = coordinator.start(control -> {
            control.checkpoint(E3DiagnosticRunCoordinator.Phase.COMMIT);
            assertTrue(control.authorizePublication(candidate));
            authorized.countDown();
            await(resolve);
            control.checkCancelled();
            control.publicationResolved(candidate, VirtualSdGenerationStore.Outcome.COMMITTED);
            finished.countDown();
            return result(VirtualSdGenerationStore.Outcome.COMMITTED, true);
        });
        assertTrue(authorized.await(2, TimeUnit.SECONDS));
        started.handle.requestCancel();
        assertTrue(started.handle.snapshot().cancellationRequested);
        assertEquals(candidate, started.handle.snapshot().candidateGenerationId);
        resolve.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        awaitTerminal(started.handle);
        assertEquals(VirtualSdGenerationStore.Outcome.COMMITTED,
                started.handle.snapshot().result.outcome);
    }

    @Test public void activityObserverCanBeReplacedWithoutReplacingTheRun() throws Exception {
        E3DiagnosticRunCoordinator coordinator = new E3DiagnosticRunCoordinator();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch report = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicInteger oldObserverChanges = new AtomicInteger();
        AtomicInteger newObserverChanges = new AtomicInteger();
        E3DiagnosticRunCoordinator.StartResult started = coordinator.start(control -> {
            entered.countDown();
            await(report);
            control.report("recreated activity owns this notification");
            finish.countDown();
            return result(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        E3DiagnosticRunCoordinator.Observer oldObserver = snapshot -> oldObserverChanges.incrementAndGet();
        E3DiagnosticRunCoordinator.Observer newObserver = snapshot -> {
            newObserverChanges.incrementAndGet();
            if (snapshot.phase == E3DiagnosticRunCoordinator.Phase.FINISHED) terminal.countDown();
        };
        started.handle.attachObserver(oldObserver);
        int oldInitial = oldObserverChanges.get();
        started.handle.attachObserver(newObserver);
        started.handle.detachObserver(oldObserver);
        report.countDown();
        assertTrue(finish.await(2, TimeUnit.SECONDS));
        assertTrue(terminal.await(2, TimeUnit.SECONDS));
        assertEquals("detached activity receives no stale callbacks", oldInitial, oldObserverChanges.get());
        assertTrue(newObserverChanges.get() >= 2);
        assertEquals(started.handle.id(), started.handle.snapshot().runId);
    }

    private static E3DiagnosticRunCoordinator.RunResult result(
            VirtualSdGenerationStore.Outcome outcome, boolean release) {
        return new E3DiagnosticRunCoordinator.RunResult(outcome, null, null, outcome.name(), release);
    }

    private static void awaitTerminal(E3DiagnosticRunCoordinator.RunHandle handle) throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        E3DiagnosticRunCoordinator.Observer observer = snapshot -> {
            if (snapshot.phase == E3DiagnosticRunCoordinator.Phase.FINISHED && !handle.isActive())
                terminal.countDown();
        };
        handle.attachObserver(observer);
        assertTrue(terminal.await(2, TimeUnit.SECONDS));
        handle.detachObserver(observer);
        assertEquals(E3DiagnosticRunCoordinator.Phase.FINISHED, handle.snapshot().phase);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("worker barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
