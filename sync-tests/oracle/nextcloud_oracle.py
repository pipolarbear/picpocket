"""Nextcloud WebDAV oracle for test verification."""

import json
import logging
import re
import time
import xml.etree.ElementTree as ET
from typing import Optional

import requests
from requests.auth import HTTPBasicAuth

from .utils import put_collection

logger = logging.getLogger(__name__)


class NextcloudOracle:
    """Verify file state via Nextcloud WebDAV API."""

    def __init__(
        self,
        base_url: str = "http://localhost:8080",
        user: str = "testuser",
        password: str = "testpass123",
        subdir: str = "",
    ):
        self.base_url = base_url
        self.user = user
        self.auth = HTTPBasicAuth(user, password)
        self.webdav_base = f"{base_url}/remote.php/dav/files/{user}"
        # Worker isolation: subdir is this worker's root folder relative to the
        # shared PicPocketTest mount (e.g. "w1"). Empty = the default root.
        # root is the full WebDAV folder path, so "w2" becomes PicPocketTest/w2.
        self.root = f"PicPocketTest/{subdir}" if subdir else "PicPocketTest"

    def _map_path(self, path: str) -> str:
        """Rewrite a caller-supplied /PicPocketTest/... path onto this worker's
        root folder (e.g. /PicPocketTest/w1/...)."""
        if self.root != "PicPocketTest" and path.startswith("/PicPocketTest"):
            return "/" + self.root + path[len("/PicPocketTest"):]
        return path

    def _webdav_url(self, path: str) -> str:
        return f"{self.webdav_base}/{self._map_path(path).lstrip('/')}"

    def _parent_href(self, path: str) -> str:
        """Return the href that PROPFIND returns for the collection itself."""
        return f"/remote.php/dav/files/{self.user}/{self._map_path(path).lstrip('/')}"

    def file_exists(self, path: str) -> bool:
        resp = requests.request(
            "PROPFIND", self._webdav_url(path), auth=self.auth, timeout=10
        )
        return resp.status_code == 207

    def _list_entries(self, path: str = "/PicPocketTest") -> list[tuple[str, bool]]:
        """PROPFIND Depth:1, returning (name, is_collection) for each entry."""
        resp = requests.request(
            "PROPFIND",
            self._webdav_url(path),
            auth=self.auth,
            headers={"Depth": "1"},
            timeout=10,
        )
        if resp.status_code != 207:
            return []
        root = ET.fromstring(resp.content)
        ns = {"d": "DAV:"}
        parent_href = self._parent_href(path)
        entries = []
        for resp_elem in root.findall(".//d:response", ns):
            href = resp_elem.find("d:href", ns)
            if href is None:
                continue
            href_text = href.text.rstrip("/")
            if href_text == parent_href.rstrip("/"):
                continue
            name = href_text.split("/")[-1]
            if not name:
                continue
            rt = resp_elem.find("d:propstat/d:prop/d:resourcetype", ns)
            is_collection = rt is not None and rt.find("d:collection", ns) is not None
            entries.append((name, is_collection))
        return entries

    def list_files(self, path: str = "/PicPocketTest") -> list[str]:
        return [name for name, _ in self._list_entries(path)]

    def list_all_files(self, path: str = "/PicPocketTest") -> list[str]:
        """Recursive listing with Depth:infinity."""
        resp = requests.request(
            "PROPFIND",
            self._webdav_url(path),
            auth=self.auth,
            headers={"Depth": "infinity"},
            timeout=30,
        )
        if resp.status_code != 207:
            return []
        root = ET.fromstring(resp.content)
        ns = {"d": "DAV:"}
        parent_href = self._parent_href(path)
        files = []
        for resp_elem in root.findall(".//d:response", ns):
            href = resp_elem.find("d:href", ns)
            if href is not None:
                href_text = href.text.rstrip("/")
                if href_text == parent_href.rstrip("/"):
                    continue
                name = href_text.split("/")[-1]
                if name:
                    files.append(name)
        return files

    def get_file_content(self, path: str) -> bytes:
        resp = requests.get(self._webdav_url(path), auth=self.auth, timeout=10)
        resp.raise_for_status()
        return resp.content

    def head_file(self, path: str) -> tuple[bool, Optional[int]]:
        """PROPFIND a single file, returning (exists, content_length in bytes).

        path is relative to PicPocketTest, e.g. \"doc-xxx/page_001.jpg\".
        """
        url = self._webdav_url(path)
        resp = requests.request(
            "PROPFIND",
            url,
            auth=self.auth,
            headers={"Depth": "0"},
            timeout=10,
        )
        if resp.status_code != 207:
            g = requests.get(url, auth=self.auth, timeout=10)
            logger.warning(
                "head_file %s PROPFIND HTTP %d; GET HTTP %d len=%d",
                path, resp.status_code, g.status_code, len(g.content),
            )
            return False, None
        root = ET.fromstring(resp.content)
        ns = {"d": "DAV:"}
        length_el = root.find(".//d:propstat/d:prop/d:getcontentlength", ns)
        if length_el is None or length_el.text is None:
            return True, None
        try:
            return True, int(length_el.text)
        except ValueError:
            return True, None

    def _child_length(self, path: str) -> Optional[int]:
        """Return content_length of a file via its parent folder Depth:1 listing.

        Direct Depth:0 PROPFIND / GET on an uploaded file can 404 even when the
        folder listing shows it, so verify through the listing that works.
        """
        parts = path.split("/")
        name = parts[-1]
        parent = "/".join(parts[:-1]) if len(parts) > 1 else ""
        folder = f"/PicPocketTest/{parent}" if parent else "/PicPocketTest"
        resp = requests.request(
            "PROPFIND",
            self._webdav_url(folder),
            auth=self.auth,
            headers={"Depth": "1"},
            timeout=10,
        )
        if resp.status_code != 207:
            return None
        root = ET.fromstring(resp.content)
        ns = {"d": "DAV:"}
        for resp_elem in root.findall(".//d:response", ns):
            href = resp_elem.find("d:href", ns)
            if href is None:
                continue
            if href.text.rstrip("/").split("/")[-1] != name:
                continue
            rt = resp_elem.find("d:propstat/d:prop/d:resourcetype", ns)
            if rt is not None and rt.find("d:collection", ns) is not None:
                return None
            length_el = resp_elem.find(
                "d:propstat/d:prop/d:getcontentlength", ns
            )
            if length_el is not None and length_el.text is not None:
                try:
                    return int(length_el.text)
                except ValueError:
                    pass
            return None
        return None

    def list_folder_with_lengths(self, path: str = "/PicPocketTest") -> dict[str, Optional[int]]:
        """Return {filename: content_length} for the files directly under `path`
        using a single Depth:1 PROPFIND.

        Batches the per-file _child_length calls so integrity checks of an
        N-file doc folder cost one listing instead of N.
        """
        resp = requests.request(
            "PROPFIND",
            self._webdav_url(path),
            auth=self.auth,
            headers={"Depth": "1"},
            timeout=10,
        )
        if resp.status_code != 207:
            return {}
        root = ET.fromstring(resp.content)
        ns = {"d": "DAV:"}
        parent_href = self._parent_href(path)
        result: dict[str, Optional[int]] = {}
        for resp_elem in root.findall(".//d:response", ns):
            href = resp_elem.find("d:href", ns)
            if href is None:
                continue
            href_text = href.text.rstrip("/")
            if href_text == parent_href.rstrip("/"):
                continue
            name = href_text.split("/")[-1]
            if not name:
                continue
            rt = resp_elem.find("d:propstat/d:prop/d:resourcetype", ns)
            if rt is not None and rt.find("d:collection", ns) is not None:
                continue
            length_el = resp_elem.find("d:propstat/d:prop/d:getcontentlength", ns)
            if length_el is not None and length_el.text is not None:
                try:
                    result[name] = int(length_el.text)
                except ValueError:
                    result[name] = None
            else:
                result[name] = None
        return result

    def verify_file_complete(
        self, path: str, expected_bytes: int = 1, timeout: float = 60.0
    ) -> bool:
        """Wait until the file has content_length >= expected_bytes.

        Returns True once the file is fully written on the WebDAV side.
        Verified via the parent folder listing, which reflects uploaded files
        even when direct Depth:0 PROPFIND / GET on the file would 404.
        """
        deadline = time.time() + timeout
        last: Optional[int] = None
        while True:
            last = self._child_length(path)
            if last is not None and last >= expected_bytes:
                return True
            if time.time() >= deadline:
                break
            time.sleep(2)
        logger.warning(
            "verify_file_complete %s timed out: expected>=%d, got length=%s",
            path, expected_bytes, last,
        )
        return False

    def list_docs(self) -> list[dict]:
        """Return list of docs with name key for test assertions."""
        return [{"name": f} for f in self.list_files("/PicPocketTest")]

    def wait_for_doc(self, prefix: str, timeout: float = 120.0) -> Optional[dict]:
        """Wait until a doc whose name starts with `prefix` appears in Drive.

        Deprecated: doc folders are UUID-named (not title-derived), so a
        title prefix never matches. Use wait_for_doc_folder instead.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            for doc in self.list_docs():
                if doc["name"].startswith(prefix):
                    return doc
            time.sleep(2)
        return None

    def wait_for_doc_folder(self, timeout: float = 120.0) -> Optional[str]:
        """Wait until a doc folder (non-hidden directory) appears in Drive.

        Mirrors test_saf_to_drive._assert_verified_completion: doc folders are
        named by UUID, so detect them by directory-ness, not by title prefix.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            for name, is_col in self._list_entries("/PicPocketTest"):
                if is_col and not name.startswith("."):
                    return name
            time.sleep(2)
        return None

    def write_file(self, path: str, content: str, mime_type: str = "text/plain"):
        """Write a file to PicPocketTest via WebDAV.

        path is relative to PicPocketTest, e.g. \"test-3page/page_004.jpg\".
        """
        url = self._webdav_url(f"/PicPocketTest/{path}")
        parent = "/".join(path.split("/")[:-1])
        if parent:
            put_collection(self._webdav_url(f"/PicPocketTest/{parent}"), self.auth)
        resp = requests.put(
            url,
            data=content.encode("utf-8") if isinstance(content, str) else content,
            auth=self.auth,
            headers={"Content-Type": mime_type},
            timeout=10,
        )
        resp.raise_for_status()
        logger.info("Wrote %s (%dB, %s)", url, len(content), mime_type)

    def read_metadata(self, doc_id: str) -> Optional[tuple[dict, int, int]]:
        """Read the newest metadata.{V}.{P}.json for a doc.

        Returns (metadata_dict, version, passphrase), or None if the doc has
        no versioned metadata file on the server.
        If metadata is encrypted (starts with PKE1 magic header), returns
        (None, version, passphrase) since the oracle cannot decrypt it.
        """
        newest = self._newest_metadata(doc_id)
        if newest is None:
            return None
        name, version, passphrase = newest
        url = self._webdav_url(f"/PicPocketTest/{doc_id}/{name}")
        resp = requests.get(url, auth=self.auth, timeout=10)
        if resp.status_code != 200:
            return None
        content = resp.content
        if content.startswith(b"PKE1"):
            return None, version, passphrase
        return json.loads(content), version, passphrase

    def wait_for_metadata(self, doc_id: str,
                          timeout: float = 60.0) -> Optional[tuple[dict, int, int]]:
        """Poll read_metadata until the doc's versioned metadata is listed.

        The Nextcloud bridge/filecache serves folder listings from a cache that
        lags server writes by a refresh cycle, so a just-synced metadata file
        can be missing from the listing for a few seconds. read_metadata()
        returns None in that window; converge here instead of unpacking None.
        Returns the same tuple as read_metadata, or None on timeout.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            result = self.read_metadata(doc_id)
            if result is not None:
                return result
            time.sleep(2)
        return None

    def _metadata_entries(self, doc_id: str) -> list[tuple[str, int, int]]:
        """Return (name, version, passphrase) for each metadata.*.json file."""
        entries = []
        for name, is_col in self._list_entries(f"/PicPocketTest/{doc_id}"):
            if is_col:
                continue
            m = re.match(r"metadata\.(\d+)\.(\d+)\.json$", name)
            if m:
                entries.append((name, int(m.group(1)), int(m.group(2))))
        return entries

    def _newest_metadata(self, doc_id: str) -> Optional[tuple[str, int, int]]:
        """Return the (name, version, passphrase) of the highest-versioned
        metadata file for a doc, or None if none exists."""
        entries = self._metadata_entries(doc_id)
        if not entries:
            return None
        return max(entries, key=lambda e: e[1])

    def write_metadata(self, doc_id: str, metadata: dict, version: int,
                       passphrase: int) -> None:
        """Write metadata.{V}.{P}.json for a doc, removing any other metadata
        file in the folder so exactly one versioned metadata file remains."""
        name = f"metadata.{version}.{passphrase}.json"
        self.write_file(f"{doc_id}/{name}", json.dumps(metadata), "application/json")
        for other, _, _ in self._metadata_entries(doc_id):
            if other != name:
                self._delete_entry(other, base_path=f"/PicPocketTest/{doc_id}")

    def _delete_entry(self, name: str, base_path: str = "/PicPocketTest",
                      retries: int = 3, delay: float = 2.0) -> bool:
        """DELETE a single entry, retrying on transient failures.

        Nextcloud's rmdir() on a directory can fail with EIO (mapped to HTTP
        403) when the rclone FUSE mount is still flushing the app's async
        uploads to Google Drive. Retrying after a delay lets the mount settle.
        """
        path = f"{base_path}/{name}"
        for attempt in range(retries):
            resp = requests.delete(self._webdav_url(path), auth=self.auth, timeout=10)
            if resp.status_code in (204, 404):
                return True
            if attempt < retries - 1:
                logger.warning(
                    "Delete %s returned HTTP %d (attempt %d/%d), retrying",
                    path, resp.status_code, attempt + 1, retries,
                )
                time.sleep(delay)
        logger.warning("Failed to delete %s: HTTP %d", path, resp.status_code)
        return False

    def _delete_recursive(self, name: str, base_path: str = "/PicPocketTest",
                          retries: int = 3, delay: float = 2.0) -> bool:
        """Delete a collection recursively: children first, then the collection.

        A bare DELETE on a non-empty collection can return 403 (Nextcloud
        external storage refuses to remove a non-empty dir), so children are
        removed before the parent. Returns True on full success.
        """
        path = f"{base_path}/{name}"
        ok = True
        for child, is_col in self._list_entries(path):
            if is_col:
                if not self._delete_recursive(child, path, retries, delay):
                    ok = False
            else:
                if not self._delete_entry(child, path, retries, delay):
                    ok = False
        if not self._delete_entry(name, base_path, retries, delay):
            ok = False
        return ok

    def clear_all(self, timeout: float = 60.0) -> None:
        """Delete all files in PicPocketTest folder.

        Collections are removed recursively (children first) because Nextcloud
        external storage returns 403 for a bare DELETE on a non-empty dir.
        Deletes are retried with backoff to ride out rclone FUSE flush lag,
        and the folder is verified empty before returning.
        """
        deadline = time.time() + timeout
        while True:
            remaining = self._list_entries("/PicPocketTest")
            if not remaining:
                break
            for name, is_collection in remaining:
                if is_collection:
                    if not self._delete_recursive(name):
                        logger.warning(
                            "Failed to delete collection %s from PicPocketTest", name
                        )
                else:
                    self._delete_entry(name)
            if time.time() > deadline:
                leftovers = [name for name, _ in self._list_entries("/PicPocketTest")]
                if leftovers:
                    logger.error(
                        "PicPocketTest not fully cleared within %.0fs, remaining: %s",
                        timeout, leftovers,
                    )
                else:
                    logger.info("Cleared PicPocketTest folder (verified empty)")
                return
            time.sleep(2)
        logger.info("Cleared PicPocketTest folder (verified empty)")
