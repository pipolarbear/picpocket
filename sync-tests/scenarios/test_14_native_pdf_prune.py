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


class TestNativePdfPrune:
    """Reference-aware deletion of the single shared born-digital PDF file.

    A born-digital import stores the whole PDF once as `<sha256>.pdf`; every
    page references that same file (with a distinct pdfPageIndex). Pruning must
    therefore be reference-aware: dropping one page keeps the file as long as
    another page still references it, and dropping the last referencing page
    prunes it from the Drive.
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

    @staticmethod
    def _stage_edit(emu, doc_id: str, meta: dict, new_version: int, passphrase: int,
                    add_files: dict | None = None, remove_files: list[str] | None = None):
        """Apply a local edit while the app is stopped (mirrors the app's own write).

        Writes the new metadata at `new_version`, adds/removes page files, then
        removes every older metadata version so `metadataVersion` reads the new
        one. All writes go through /data/local/tmp + `run-as` because /sdcard is
        not readable via run-as on API 34.
        """
        emu.adb.shell(f"am force-stop {APP_PACKAGE}")
        time.sleep(1)
        new_meta = f"metadata.{new_version}.{passphrase}.json"

        # New metadata.
        with tempfile.NamedTemporaryFile(suffix=".json", mode="w") as meta_tmp:
            json.dump(meta, meta_tmp)
            meta_tmp.flush()
            staged_meta = "/data/local/tmp/picpocket_new_meta.json"
            emu.adb.push(meta_tmp.name, staged_meta)
            emu.adb.shell(f"chmod 666 {staged_meta}")
        emu.adb.shell(
            f"run-as {APP_PACKAGE} sh -c 'cat {staged_meta} > files/documents/{doc_id}/{new_meta}'"
        )

        # New page files.
        for filename, content in (add_files or {}).items():
            with tempfile.NamedTemporaryFile(suffix=".jpg") as page_tmp:
                page_tmp.write(content)
                page_tmp.flush()
                staged_page = "/data/local/tmp/picpocket_new_page.jpg"
                emu.adb.push(page_tmp.name, staged_page)
                emu.adb.shell(f"chmod 666 {staged_page}")
            emu.adb.shell(
                f"run-as {APP_PACKAGE} sh -c 'cat {staged_page} > files/documents/{doc_id}/{filename}'"
            )

        # Dropped page files.
        for filename in (remove_files or []):
            emu.adb.shell(f"run-as {APP_PACKAGE} rm -f files/documents/{doc_id}/{filename}")

        # Remove every older metadata version.
        listing = emu.adb.shell(
            f"run-as {APP_PACKAGE} ls files/documents/{doc_id}/ 2>/dev/null || true",
            timeout=5,
        )
        for name in (listing or "").split():
            if re.match(r"metadata\.\d+\.\d+\.json$", name) and name != new_meta:
                emu.adb.shell(f"run-as {APP_PACKAGE} rm -f files/documents/{doc_id}/{name}")

        return new_meta

    def test_shared_pdf_file_pruned_only_when_last_page_dropped(self, emu_a, watcher_a, oracle):
        generate_and_push(emu_a.adb, "test-native-prune", pages=2)
        emu_a.open_app()
        emu_a.import_pdf("test-native-prune.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after initial sync"

        meta = emu_a.read_device_metadata(doc_prefix)
        assert meta is not None, "Could not read device metadata"
        pages = meta.get("pages", [])
        assert len(pages) == 2, f"Expected 2 native pages, got {len(pages)}"
        filenames = {p["filename"] for p in pages}
        assert len(filenames) == 1, f"Expected a single shared PDF file, got {filenames}"
        shared = next(iter(filenames))
        assert shared.endswith(".pdf"), f"Shared native file is not a PDF: {shared}"

        server_files = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc_prefix}")
        assert shared in server_files, f"Shared PDF not on server after initial sync: {server_files}"

        # Edit 1: drop page 2 while page 1 still references the shared PDF.
        _, old_version, passphrase = self._local_meta_info(emu_a, doc_prefix)
        meta["pages"] = pages[:1]
        new_version = old_version + 1
        self._stage_edit(emu_a, doc_prefix, meta, new_version, passphrase)

        emu_a.open_app()
        sync_with_false_mutex_retry(emu_a, watcher_a)

        server_files = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc_prefix}")
        assert shared in server_files, (
            f"Shared PDF pruned while still referenced by a page: {server_files}"
        )
        assert f"metadata.{new_version}.{passphrase}.json" in server_files, (
            f"Pushed metadata missing on server: {server_files}"
        )

        # Edit 2: replace the last page with an image page, leaving the shared
        # PDF unreferenced, so it must be pruned.
        _, old_version, passphrase = self._local_meta_info(emu_a, doc_prefix)
        new_bytes = b"mock image page replacing the last native page"
        new_file = hashlib.sha256(new_bytes).hexdigest() + ".jpg"
        new_entry = {
            "pageNumber": 1,
            "filename": new_file,
            "fileSizeBytes": len(new_bytes),
            "createdAt": int(time.time() * 1000),
        }
        meta["pages"] = [new_entry]
        new_version = old_version + 1
        self._stage_edit(
            emu_a, doc_prefix, meta, new_version, passphrase,
            add_files={new_file: new_bytes},
            remove_files=[shared],
        )

        emu_a.open_app()
        sync_with_false_mutex_retry(emu_a, watcher_a)

        server_files = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc_prefix}")
        assert new_file in server_files, f"New image page not on server: {server_files}"
        assert shared not in server_files, (
            f"Shared PDF not pruned after its last reference was dropped: {server_files}"
        )

        assert_drive_verified(oracle)
