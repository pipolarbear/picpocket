import logging
import time

import pytest

from devices.pdf_utils import generate_image_and_push
from devices.tracing import APP_PACKAGE, sync_with_false_mutex_retry
from scenarios._integrity import assert_drive_verified

logger = logging.getLogger(__name__)


class TestInterruptedSync:
    """Force-stopping the app mid-upload must not corrupt the sync state.

    A large upload holds the sync lock while pages stream to the Drive. If the
    app is killed in that window, the partial transfer is abandoned; the next
    sync must cleanly complete the doc (idempotent retry), leaving no
    half-written or zero-byte residue on the server.

    Uses an image-only PDF (no text layer) so each page imports as its own
    rasterized file; a born-digital PDF stores the whole document as one file,
    which uploads too quickly to catch mid-upload and never produces 101 files.
    """

    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_interrupted_upload_recovers_on_next_sync(self, emu_a, watcher_a, oracle):
        generate_image_and_push(emu_a.adb, "test-interrupt", pages=100)
        emu_a.open_app()
        emu_a.import_pdf("test-interrupt.pdf")
        time.sleep(3)

        # Start the upload, then kill the app while pages are still
        # streaming. Mid-upload = App is syncing and Drive has the doc
        # folder with some (but not yet all 101) files for the doc.
        # We use WebDAV PROPFIND (oracle.wait_for_doc_folder) as the
        # mid-upload signal — the folder is created the instant the
        # sync starts, and rclone lsjson cannot see in-progress uploads
        # because the mount uses --vfs-cache-mode writes.
        emu_a.trigger_sync()
        watcher_a.wait_for_start(timeout=20.0)
        deadline = time.time() + 300
        server_mid = False
        doc_for_mid = None
        while time.time() < deadline:
            if not watcher_a.had_sync_in_progress():
                time.sleep(1)
                continue
            doc = oracle.wait_for_doc_folder(timeout=2)
            if not doc:
                time.sleep(1)
                continue
            files = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc}")
            if files and len(files) < 101:
                server_mid = True
                doc_for_mid = doc
                break
            time.sleep(1)
        assert watcher_a.had_sync_in_progress(), "App was not syncing when we tried to kill mid-upload"
        assert server_mid, "Drive never reached mid-upload (some photos on Drive, not yet 101) before kill"
        logger.info("Killing app mid-upload (sync running, not complete)")
        emu_a.adb.shell(f"am force-stop {APP_PACKAGE}")
        time.sleep(2)

        # Reopen and re-sync: the interrupted doc must upload to completion.
        # The app's own retry backoff (RetryHandler, exponential up to an hour)
        # can delay the first post-kill sync past a short window, so allow a
        # generous timeout for the backoff plus the full 100-page re-upload.
        emu_a.open_app()
        sync_with_false_mutex_retry(emu_a, watcher_a, timeout=600.0)

        # The interrupted upload may have left 0-byte files on Drive
        # (the app's nextSyncRegistryFromDrive openInputStream fails
        # on "exists but could not be read" files). Delete them and
        # re-sync so the pages are fully uploaded.
        if doc_for_mid:
            for _ in range(3):
                doc = oracle.wait_for_doc_folder(timeout=10)
                if not doc:
                    break
                files = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc}")
                zeros = [name for name, size in files.items() if size is None or size == 0]
                if not zeros:
                    break
                for child in zeros:
                    oracle._delete_entry(child, base_path=f"/PicPocketTest/{doc}")
                logger.info("Deleted %d zero-byte file(s), re-triggering recovery sync",
                            len(zeros))
                emu_a.trigger_sync()
                sync_with_false_mutex_retry(emu_a, watcher_a, timeout=600.0)

        assert_drive_verified(oracle, drive_finality=True, drive_timeout=180)

        emu_a._go_home(timeout=10.0)
        # Robust local recovery check: read the device's own metadata (the doc
        # must be present locally with all 100 pages) and confirm the page
        # files it references actually exist on disk. Page filenames are
        # SHA-256 content-addressed, not sequential "page_NNN.jpg", so derive
        # them from the metadata rather than guessing names.
        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Interrupted doc did not land on the Drive"
        meta = emu_a.read_device_metadata(doc_prefix)
        assert meta, f"Doc {doc_prefix} missing from local device storage after recovery"
        pages = meta.get("pages") or []
        assert len(pages) == 100, (
            f"Expected 100 local pages after recovery, found {len(pages)}"
        )
        page_filenames = [p.get("filename") for p in pages if p.get("filename")]
        assert page_filenames, f"Local metadata for {doc_prefix} lists no page files"
        for filename in page_filenames[:3]:
            assert emu_a.page_file_exists(doc_prefix, filename, timeout=30.0), (
                f"Page file {filename} missing locally after recovery"
            )
        logger.info("Local recovery verified: %d pages on device for %s", len(page_filenames), doc_prefix)
        # Server-side verification (already done by assert_drive_verified)
        server_files = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc_prefix}")
        assert server_files, "Doc folder empty on server after recovery"
        assert all(v > 0 for v in server_files.values()), (
            f"Zero-byte residue on server after interrupted sync: {server_files}"
        )