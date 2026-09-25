import logging
import re
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import (
    MUTEX_LOCKED,
    SYNC_COMPLETE,
    SYNC_GATING,
    sync_until,
    sync_with_false_mutex_retry,
)
from scenarios._integrity import assert_drive_verified

logger = logging.getLogger(__name__)

# The lock is refused while another device holds it; the loser's sync aborts
# with "performSync: mutex locked by another device" and returns to Idle
# without touching the Drive.
_LOCK_OR_COMPLETE = re.compile(
    r"(?:performSync:\s*complete|mutex locked by another device)"
)


class TestContention:
    """Simultaneous syncs on two devices must be serialized by the mutex.

    A big upload on A holds the sync lock for many seconds, so B's sync
    started a moment later must be refused with "mutex locked by another
    device" instead of racing A's writes. After A releases the lock, B re-syncs
    cleanly and converges.
    """

    @pytest.fixture(autouse=True)
    def setup(self, reset_state, reset_state_b, ensure_drive_configured, emu_a, emu_b):
        self.emu_a = emu_a
        self.emu_b = emu_b

    def test_simultaneous_syncs_serialize_and_converge(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices
    ):
        # Baseline: A uploads one small doc so both devices have a common base.
        generate_and_push(emu_a.adb, "test-contention", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-contention.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)
        assert_drive_verified(oracle, drive_finality=True)

        emu_b.ensure_drive_configured()

        # ensure_drive_configured leaves B's auto-sync in flight (it started
        # ~14s after folder selection in the failing run, mid-pair, inverting
        # the intended A-first order). Wait it out so the contention pair
        # starts from a quiet state and A's trigger really is first.
        sync_with_false_mutex_retry(emu_b, watcher_b, trigger=False, timeout=150.0)

        locked_observed = False
        for attempt in range(2):
            # Give A a large upload so its sync holds the lock for ~15-30s.
            generate_and_push(
                emu_a.adb, f"test-contention-big-{attempt}", pages=20
            )
            emu_a.open_app()
            emu_a.import_pdf(f"test-contention-big-{attempt}.pdf")
            time.sleep(3)

            # Fire both syncs back-to-back: A acquires the lock, B hits it.
            # Clear both SyncManager buffers first so the completed-vs-locked
            # checks below can only match lines from this pair, never stale
            # lines from earlier syncs (the watchers scan the whole buffer).
            watcher_a.device.logcat_clear()
            watcher_b.device.logcat_clear()
            emu_a.trigger_sync()
            assert watcher_a.had_sync_in_progress(), (
                "A's big-upload sync never started"
            )
            # Wait until A has acquired the mutex and is past gating BEFORE
            # firing B. Firing both back-to-back makes the settle race favor
            # whichever client writes the lock last (usually B, the later
            # starter), so A was the one refused and the test watched the wrong
            # device. The "gating" line is logged only after acquire succeeds,
            # so it proves A holds the lock.
            watcher_a.wait_for_pattern(SYNC_GATING, timeout=60.0)
            emu_b.trigger_sync()

            # A must complete its (long) upload — allow the same spurious
            # lock-abort retries as elsewhere, but never re-trigger the first
            # wait since A's broadcast already started the sync.
            sync_with_false_mutex_retry(
                emu_a, watcher_a, trigger=False, timeout=240.0
            )

            # B must have been refused by the lock while A held it.
            try:
                b_line = watcher_b.wait_for_pattern(MUTEX_LOCKED, timeout=120.0)
                locked_observed = True
                logger.info("Contention observed on B: %s", b_line)
                break
            except TimeoutError:
                if SYNC_COMPLETE.search(watcher_b.wait_for_pattern(
                    SYNC_COMPLETE, timeout=30.0
                )):
                    logger.warning(
                        "B completed instead of locking (attempt %d); A's sync "
                        "did not hold the lock long enough — retrying the pair",
                        attempt + 1,
                    )
                    continue
                raise

        assert locked_observed, (
            "No sync was ever refused by the mutex across 2 contention attempts"
        )

        # After A released the lock, B re-syncs and converges on every doc.
        sync_with_false_mutex_retry(emu_b, watcher_b, timeout=240.0)
        assert_drive_verified(oracle, drive_finality=True)
        emu_b._go_home(timeout=10.0)
        # B's post-contention sync may read a stale listing (missing big-0)
        # and complete as a no-op; re-sync until each doc actually lands.
        assert sync_until(
            emu_b, watcher_b,
            check=lambda: emu_b.find_local_doc("test-contention") is not None,
        ), "Baseline doc not present on B after contention"
        assert sync_until(
            emu_b, watcher_b,
            check=lambda: emu_b.find_local_doc("test-contention-big-0") is not None,
        ), "Big doc not present on B after contention"