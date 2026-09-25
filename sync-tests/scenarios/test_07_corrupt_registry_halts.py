import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import (
    REGISTRY_CORRUPT,
    sync_with_false_mutex_retry,
    wait_for_pattern_with_false_mutex_retry,
)

logger = logging.getLogger(__name__)

GARBAGE_REGISTRY = "this is not valid json {"  # 17 bytes of trash


class TestCorruptRegistry:
    """A corrupt devices.json on the Drive must halt the sync without being
    overwritten.

    The app reads the registry before the reconcile loop and before writing
    its own registry copy back (syncRegistryFromDrive runs before
    syncRegistryToDrive), so a corrupt registry aborts the sync and the
    server's devices.json must still be the garbage afterwards.
    """

    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_corrupt_registry_halts_sync_and_is_not_overwritten(
        self, emu_a, watcher_a, oracle
    ):
        generate_and_push(emu_a.adb, "test-registry", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-registry.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)
        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after baseline sync"

        # The Nextcloud bridge serves folder listings from its own DB, which
        # lags out-of-band writes by one refresh cycle: the first sync after
        # corrupting devices.json may read a stale-but-valid copy from the
        # bridge cache and rewrite a valid registry to the server. Each sync
        # triggers the client's folder refresh, so re-corrupting and retrying
        # converges: eventually a sync reads the garbage and aborts.
        halted = False
        for attempt in range(5):
            oracle.write_file("devices.json", GARBAGE_REGISTRY)
            try:
                wait_for_pattern_with_false_mutex_retry(
                    emu_a, watcher_a, REGISTRY_CORRUPT
                )
                halted = True
                break
            except TimeoutError:
                logger.warning(
                    "Corrupt registry not observed on attempt %d "
                    "(bridge likely served a stale valid registry); re-corrupting",
                    attempt + 1,
                )
        assert halted, "App never reported a corrupt registry over 5 attempts"

        # The corrupt read halts the sync BEFORE the app writes its registry
        # back, so the server's devices.json must still be the exact garbage.
        content = oracle.get_file_content("/PicPocketTest/devices.json").decode()
        assert content == GARBAGE_REGISTRY, (
            "App overwrote the corrupt devices.json instead of halting: "
            f"{content!r}"
        )

        emu_a._go_home(timeout=10.0)
        assert emu_a.find_local_doc("test-registry"), (
            "Doc not present after registry-halted sync"
        )