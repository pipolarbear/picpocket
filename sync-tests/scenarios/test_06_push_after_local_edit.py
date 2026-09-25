import hashlib
import json
import logging
import re
import tempfile
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import APP_PACKAGE, sync_with_false_mutex_retry
from scenarios._integrity import assert_drive_verified

logger = logging.getLogger(__name__)


class TestPushAfterLocalEdit:
    """A local edit made while the app is stopped must PUSH to the Drive.

    The edit is applied the same way the app would: bump the on-disk metadata
    version, add a page file, and drop an old page. Because there is no UI for
    editing pages out-of-band, the test stages the new files on /sdcard and
    copies them into the app's private storage with `run-as`, while the app is
    force-stopped so no sync can race the edit.
    """

    @pytest.fixture(autouse=True)
    def setup(self, reset_state, ensure_drive_configured, emu_a, oracle):
        self.emu_a = emu_a
        self.oracle = oracle

    @staticmethod
    def _local_meta_info(emu, doc_id: str):
        """Return (newest_metadata_name, version, passphrase) from device storage."""
        out = emu.adb.shell(
            f"run-as {APP_PACKAGE} ls files/documents/{doc_id}/ 2>/dev/null || true",
            timeout=5,
        )
        names = [
            n for n in (out or "").split()
            if re.match(r"metadata\.\d+\.\d+\.json$", n)
        ]
        assert names, f"No versioned metadata on device for doc {doc_id}"
        newest = max(names, key=lambda n: int(n.split(".")[1]))
        version = int(newest.split(".")[1])
        passphrase = int(newest.split(".")[2])
        return newest, version, passphrase

    def test_local_edit_pushes_and_prunes_remotes(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-push", pages=2)
        emu_a.open_app()
        emu_a.import_pdf("test-push.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after initial sync"
        old_meta, old_version, old_passphrase = self._local_meta_info(emu_a, doc_prefix)
        meta = emu_a.read_device_metadata(doc_prefix)
        assert meta is not None, "Could not read device metadata"
        pages = meta.get("pages", [])
        assert len(pages) >= 2, "Local doc should have at least two pages"
        keep = pages[:1]
        dropped = pages[1:]

        # New page: content-addressed like the app names pages (sha256 of bytes).
        new_bytes = b"mock page added by a local edit while the app was stopped"
        new_file = hashlib.sha256(new_bytes).hexdigest() + ".jpg"
        new_entry = dict(pages[0])
        new_entry["filename"] = new_file
        new_entry["pageNumber"] = len(keep) + 1
        new_entry["fileSizeBytes"] = len(new_bytes)
        new_entry["createdAt"] = int(time.time() * 1000)
        meta["pages"] = keep + [new_entry]
        new_version = old_version + 1
        new_meta = f"metadata.{new_version}.{old_passphrase}.json"

        # Stage the edit while the app is stopped so no sync races it.
        # /sdcard is not readable via run-as on API 34 (scoped storage), so
        # stage in /data/local/tmp (shell-writable, app-readable via run-as)
        # and copy with cat, matching conftest's tracing pattern.
        emu_a.adb.shell(f"am force-stop {APP_PACKAGE}")
        time.sleep(1)
        staged_page = "/data/local/tmp/picpocket_new_page.jpg"
        staged_meta = "/data/local/tmp/picpocket_new_meta.json"
        with tempfile.NamedTemporaryFile(suffix=".jpg") as page_tmp:
            page_tmp.write(new_bytes)
            page_tmp.flush()
            emu_a.adb.push(page_tmp.name, staged_page)
            emu_a.adb.shell(f"chmod 666 {staged_page}")
        with tempfile.NamedTemporaryFile(suffix=".json", mode="w") as meta_tmp:
            json.dump(meta, meta_tmp)
            meta_tmp.flush()
            emu_a.adb.push(meta_tmp.name, staged_meta)
            emu_a.adb.shell(f"chmod 666 {staged_meta}")

        emu_a.adb.shell(
            f"run-as {APP_PACKAGE} sh -c 'cat {staged_page} > files/documents/{doc_prefix}/{new_file}'"
        )
        emu_a.adb.shell(
            f"run-as {APP_PACKAGE} sh -c 'cat {staged_meta} > files/documents/{doc_prefix}/{new_meta}'"
        )
        emu_a.adb.shell(f"run-as {APP_PACKAGE} rm -f files/documents/{doc_prefix}/{old_meta}")
        for page in dropped:
            emu_a.adb.shell(
                f"run-as {APP_PACKAGE} rm -f files/documents/{doc_prefix}/{page['filename']}"
            )

        # Sanity-check the local edit landed before reopening the app.
        local_listing = emu_a.adb.shell(
            f"run-as {APP_PACKAGE} ls files/documents/{doc_prefix}/ 2>/dev/null || true",
            timeout=5,
        )
        listing = (local_listing or "").split()
        assert new_file in listing, f"Staged page not in device storage: {listing}"
        assert new_meta in listing, f"Staged metadata not in device storage: {listing}"
        assert old_meta not in listing, "Old metadata still present after edit"
        for page in dropped:
            assert page["filename"] not in listing, (
                f"Dropped page still present after edit: {page['filename']}"
            )

        # Reopen and sync: local version (V+1) now exceeds the remote version,
        # so the app must push — upload the new page, delete the remote page it
        # no longer references, and write metadata at V+1.
        emu_a.open_app()
        sync_with_false_mutex_retry(emu_a, watcher_a)

        server_files = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc_prefix}")
        assert new_file in server_files, f"New page not on server: {server_files}"
        assert server_files.get(new_file) == len(new_bytes), (
            f"New page size mismatch on server: {server_files.get(new_file)}"
        )
        for page in dropped:
            assert page["filename"] not in server_files, (
                f"Pruned page still on server: {page['filename']}"
            )
        assert f"metadata.{new_version}.{old_passphrase}.json" in server_files, (
            f"Pushed metadata missing on server: {server_files}"
        )
        assert old_meta not in server_files, (
            f"Old metadata version still on server: {server_files}"
        )

        emu_a._go_home(timeout=10.0)
        # Store is the ground truth; the home UI can lag a recomposition.
        assert emu_a.find_local_doc("test-push"), "Doc not present locally after push"
        assert emu_a.page_file_exists(doc_prefix, new_file, timeout=5.0), (
            "New page file missing locally after push"
        )
        assert_drive_verified(oracle, drive_finality=True)