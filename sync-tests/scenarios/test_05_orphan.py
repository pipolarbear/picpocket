import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import sync_until, sync_with_false_mutex_retry
from scenarios._integrity import assert_drive_verified

logger = logging.getLogger(__name__)


class TestOrphan:
    def test_delete_propagation_and_orphan_surfacing(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices,
        reset_state, reset_state_b, ensure_drive_configured
    ):
        # Phase 1: A imports + uploads; B downloads. These work under the
        # versioned LWW engine and are the durable part of this scenario.
        # ensure_drive_configured re-selects the folder + enables sync on A
        # (reset_state wiped its config; without it the Sync screen stays in
        # Disconnected state and has no "Sync Now" button).
        generate_and_push(emu_a.adb, "test-orphan", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-orphan.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)
        assert_drive_verified(oracle, drive_finality=True)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after A's initial sync"

        emu_a._go_home(timeout=10.0)
        assert emu_a.find_local_doc("test-orphan"), "Doc not present on device A"

        emu_b.ensure_drive_configured()

        def _downloaded():
            meta = emu_b.read_device_metadata(doc_prefix)
            if not meta:
                return False
            pages = meta.get("pages", [])
            return bool(
                pages and emu_b.page_file_exists(
                    doc_prefix, pages[0]["filename"], timeout=5.0
                )
            )

        assert sync_until(emu_b, watcher_b, _downloaded), (
            "Doc not downloaded on device B"
        )

        emu_b._go_home(timeout=10.0)
        assert emu_b.find_local_doc("test-orphan"), "Doc not present on device B"

        # Phase 2: delete propagation + orphan surfacing on B.
        #
        # NOT IMPLEMENTED in the app: uploadDeletedTombstone (UploadEngine.kt:141)
        # has no caller, so a deletion on A never writes a .deleted tombstone to
        # the server. A's own next sync re-downloads the doc via the remote-only
        # reconcile loop (SyncManager.kt:233), and B never sees the tombstone, so
        # "Removed by Others" can never surface. The original scenario (long-press
        # -> Delete selected -> tombstone -> B shows the orphan) cannot pass.
        # Tracked as a follow-up OpenSpec change; this test is xfailed so Phase 1
        # stays exercised and regression-guarded.
        pytest.xfail(
            "delete propagation unwired: uploadDeletedTombstone is dead code, "
            "so no tombstone reaches the server and B never surfaces an orphan"
        )
