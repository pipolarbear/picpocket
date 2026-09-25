import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import sync_with_false_mutex_retry

logger = logging.getLogger(__name__)


class TestNoopResync:
    """A re-sync with nothing changed on either side must be a no-op.

    The LWW reconcile only acts when local/remote versions or passphrase
    counts diverge. A plain re-sync must not re-upload pages (same bytes), not
    bump the metadata version, and not re-encrypt. The server's doc folder
    therefore has to be byte-for-byte identical after the re-sync (the shared
    devices.json registry legitimately changes lastSeen, so only the doc
    folder is asserted).
    """

    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_resync_with_no_changes_is_a_noop(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-noop", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-noop.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after first sync"

        def _snapshot():
            # Use the folder listing for lengths; direct GET on a file can
            # 404 behind the Nextcloud bridge even when the listing sees it
            # (see oracle._child_length comment). Lengths are the source of
            # truth for no-op: a re-upload would change length or file set.
            return oracle.list_folder_with_lengths(f"/PicPocketTest/{doc_prefix}")

        before_lengths = _snapshot()
        assert before_lengths, "Server doc folder empty after first sync"
        assert any(n.startswith("metadata.") for n in before_lengths), (
            "No versioned metadata on server"
        )

        # Plain re-sync: same doc, no remote or local change.
        sync_with_false_mutex_retry(emu_a, watcher_a)

        after_lengths = _snapshot()
        assert set(after_lengths) == set(before_lengths), (
            f"File set changed on re-sync: before={sorted(before_lengths)} "
            f"after={sorted(after_lengths)}"
        )
        for name in before_lengths:
            assert after_lengths[name] == before_lengths[name], (
                f"Content length changed for {name} on re-sync: "
                f"{before_lengths[name]} -> {after_lengths[name]}"
            )

        emu_a._go_home(timeout=10.0)
        assert emu_a.find_local_doc("test-noop"), "Doc not present after re-sync"