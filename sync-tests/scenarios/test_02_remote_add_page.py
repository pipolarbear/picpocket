import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import sync_until, sync_with_false_mutex_retry

logger = logging.getLogger(__name__)

NEW_PAGE_FILE = "remote_page.jpg"
NEW_PAGE_CONTENT = "mock image data added remotely"


class TestRemoteAddPage:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    def test_oracle_adds_page_device_downloads(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-remote", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-remote.pdf")
        time.sleep(3)

        sync_with_false_mutex_retry(emu_a, watcher_a)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after initial sync"

        # Oracle adds a page out-of-band and bumps the metadata version.
        metadata = oracle.wait_for_metadata(doc_prefix)
        assert metadata is not None, "Versioned metadata not found on server"
        meta, version, passphrase = metadata
        pages = meta.get("pages", [])
        assert pages, "Doc should have at least one page on the server"
        next_page_number = max(p["pageNumber"] for p in pages) + 1
        pages.append({
            "pageNumber": next_page_number,
            "filename": NEW_PAGE_FILE,
            "fileSizeBytes": len(NEW_PAGE_CONTENT.encode()),
            "filterTypeOrdinal": 0,
            "createdAt": int(time.time() * 1000),
        })
        meta["pages"] = pages
        oracle.write_file(
            f"{doc_prefix}/{NEW_PAGE_FILE}",
            NEW_PAGE_CONTENT,
            "image/jpeg",
        )
        oracle.write_metadata(doc_prefix, meta, version + 1, passphrase)

        # Probe: the remote-added page must actually be on the server, and the
        # metadata must be bumped to the next version.
        server_files = oracle.list_files(f"/PicPocketTest/{doc_prefix}")
        assert NEW_PAGE_FILE in server_files, (
            f"{NEW_PAGE_FILE} missing on server: {server_files}"
        )
        assert f"metadata.{version + 1}.{passphrase}.json" in server_files, (
            f"bumped metadata missing on server: {server_files}"
        )

        # Sync until the app sees the fresh bridge listing and pulls: remote
        # metadata version (V+1) exceeds the device's local version, so the
        # app downloads the new page file and writes metadata at V+1.
        def _downloaded():
            return emu_a.page_file_exists(doc_prefix, NEW_PAGE_FILE, timeout=5.0)

        assert sync_until(emu_a, watcher_a, _downloaded), (
            "Device did not download remote-added page %s" % NEW_PAGE_FILE
        )

        emu_a._go_home(timeout=15.0)
        assert emu_a.find_local_doc("test-remote"), "Doc not present after page add"
