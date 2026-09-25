"""Shared server-side content-integrity assertions for scenario tests.

The happy-path scenarios used to assert only that *something* appeared on the
server (folder exists, listing non-empty), which passed even when every
uploaded file was 0 bytes. These helpers are the honest version: every file
under every synced doc folder must reach a non-zero content length on the
WebDAV side, keep that content across a short settle window, and land on
Google Drive finalized (non-zero size, real MD5, no partial-download residue).

Underscore-prefixed so pytest's scenario collector never treats this as a test
module.
"""

import logging
import time

from infra.nextcloud import scan_path, wait_drive_finalized

logger = logging.getLogger(__name__)


def wait_for_metadata(oracle, doc_id):
    """Read a doc's versioned metadata, converging over the Nextcloud bridge
    filecache lag and the rclone-backed external storage's transient 5xx.

    On timeout it forces a targeted `occ files:scan` of the doc folder and
    polls a short grace window, so a just-rewritten metadata file that the
    filecache hasn't picked up yet still resolves. Returns the same tuple as
    oracle.read_metadata, or None if it never appears.
    """
    return oracle.wait_for_metadata(
        doc_id,
        refresh=lambda: scan_path(f"PicPocketTest/{doc_id}"),
    )


def assert_doc_integrity(oracle, folder, settle=5.0, on_file_fail=None,
                         timeout=60.0) -> dict:
    """Assert every file in one doc folder has non-zero content length on the
    WebDAV side, then re-check after `settle` seconds that the content
    persisted (i.e. the server does not eventually settle back to 0 bytes).

    Uses one Depth:1 PROPFIND per poll (list_folder_with_lengths) so an N-file
    folder costs one listing per check instead of N.

    Returns {filename: content_length} so callers can size-match another
    device's local copy against the server truth.

    on_file_fail, if given, is called with (folder, child) just before a
    failure is raised, so tests can dump forensic state (e.g. logcat, occ
    scan, Drive rows) for the exact file that broke.
    """
    deadline = time.time() + timeout
    lengths: dict[str, int] = {}
    while time.time() < deadline:
        listing = oracle.list_folder_with_lengths(f"/PicPocketTest/{folder}")
        lengths = {
            name: (size or 0)
            for name, size in listing.items()
        }
        if lengths and all(v > 0 for v in lengths.values()):
            break
        time.sleep(2)

    # An empty doc folder means the upload never landed (e.g. the client's
    # createFolder MKCOL reached the server but the response timed out, leaving
    # a bare folder). Never let that pass as a successful sync.
    assert lengths, (
        f"Doc folder {folder} has no files on the server (upload never landed)"
    )
    incomplete = [f for f, v in lengths.items() if v <= 0]
    if incomplete:
        for child in incomplete:
            if on_file_fail is not None:
                on_file_fail(folder, child)
        assert False, f"WebDAV files not complete: {incomplete}"

    if settle > 0:
        time.sleep(settle)
        cur = oracle.list_folder_with_lengths(f"/PicPocketTest/{folder}")
        for child in list(lengths):
            current = cur.get(child) or 0
            if current <= 0:
                if on_file_fail is not None:
                    on_file_fail(folder, child)
                assert False, f"WebDAV file lost its content after settle: {folder}/{child}"
            lengths[child] = current
    return lengths


def assert_drive_verified(oracle, drive_timeout=60.0, on_file_fail=None,
                          drive_finality=False) -> dict:
    """Assert the sync landed end-to-end with verified completion.

    B1: every file under each synced doc folder is fully written on the
    WebDAV side (non-zero content length, stable across a settle window).

    B2 (only when drive_finality=True): the Drive copy is finalized — no
    'application/x-partial-download' residue, every file has a size and a real
    MD5. Drive finality polls real Google write-back, which adds ~10-30s per
    test, so it is opt-in (mark tests with @pytest.mark.drive_finality).

    Returns {doc_folder: {filename: content_length}} so callers can
    size-match another device's local copy against the server truth.
    """
    entries = oracle._list_entries("/PicPocketTest")
    doc_folders = [
        name for name, is_col in entries if is_col and not name.startswith(".")
    ]
    logger.info("Drive folder after sync: %s", entries)
    assert doc_folders, "No doc folders found on Drive after sync"

    doc_lengths = {}
    for folder in doc_folders:
        doc_lengths[folder] = assert_doc_integrity(
            oracle, folder, on_file_fail=on_file_fail
        )

    if not drive_finality:
        logger.info(
            "WebDAV integrity verified for %d doc folder(s) (drive_finality off)",
            len(doc_folders),
        )
        return doc_lengths

    finalized = wait_drive_finalized("", min_size=0, timeout=drive_timeout)
    assert finalized, "Drive folder empty after sync"
    partial = [
        r["Path"] for r in finalized if r.get("MimeType") == "application/x-partial-download"
    ]
    assert not partial, f"Partial-download residue on Drive: {partial}"
    no_hash = [r["Path"] for r in finalized if not (r.get("Hashes") or {}).get("md5")]
    assert not no_hash, f"Files without MD5 on Drive: {no_hash}"
    zeros = [r["Path"] for r in finalized if r.get("Size") == 0]
    assert not zeros, f"0-byte files on Drive (failed-write residue): {zeros}"
    paths = [r["Path"] for r in finalized]
    dups = sorted({p for p in paths if paths.count(p) > 1})
    assert not dups, f"Duplicate file objects on Drive: {dups}"
    logger.info("Drive finality verified: %d file(s), no partial-download residue", len(finalized))
    return doc_lengths
