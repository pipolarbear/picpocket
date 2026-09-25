import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import sync_until, sync_with_false_mutex_retry
from scenarios._integrity import wait_for_metadata

logger = logging.getLogger(__name__)


class TestLww:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_divergent_remote_higher_version_wins(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-lww", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-lww.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after initial sync"

        # Divergence: the oracle rewrites the doc at a higher version with a
        # different name. Under the journal-free LWW engine the higher version
        # silently wins; there is no conflict-resolution UI anymore.
        metadata = wait_for_metadata(oracle, doc_prefix)
        assert metadata is not None, "Versioned metadata not found on server"
        meta, version, passphrase = metadata
        meta["name"] = "test-lww-divergent"
        oracle.write_metadata(doc_prefix, meta, version + 2, passphrase)

        # Sync until the app sees the fresh bridge listing and pulls the
        # higher-versioned metadata (remote version > local), converging on
        # the divergent name.
        def _converged():
            device_meta = emu_a.read_device_metadata(doc_prefix)
            return bool(device_meta and device_meta.get("name") == "test-lww-divergent")

        assert sync_until(emu_a, watcher_a, _converged), (
            "LWW did not converge on the higher-versioned remote name"
        )

        emu_a._go_home(timeout=15.0)
        assert emu_a.find_local_doc("test-lww-divergent"), (
            "LWW did not converge on the higher-versioned remote name"
        )
        assert emu_a.find_local_doc("test-lww") is None, (
            "Old divergent name still present after LWW"
        )

        # The Sync screen no longer surfaces a conflict count for divergence.
        emu_a.open_settings()
        emu_a.d(text="Sync").click()
        time.sleep(2)
        assert not emu_a.d(textContains="Conflicts").exists, (
            "Conflict UI unexpectedly surfaced"
        )
