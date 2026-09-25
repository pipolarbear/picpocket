import json
import logging
import os
import re
import time
from typing import Optional

import uiautomator2 as u2

from .adb import AdbDevice
from .tracing import SyncWatcher

logger = logging.getLogger(__name__)

APP_PACKAGE = "com.picpocket.app"


def worker_root_folder() -> str:
    """The sync root folder the app selects in the SAF picker.

    Default worker roots at PicPocketTest itself; a parallel worker
    (NEXTCLOUD_SUBDIR=w1) roots at PicPocketTest/w1, which keeps its
    devices.json + sync-lock.json in a private subfolder of the shared Drive
    root.
    """
    subdir = os.environ.get("NEXTCLOUD_SUBDIR", "").strip()
    return f"PicPocketTest/{subdir}" if subdir else "PicPocketTest"


class UiDevice:
    def __init__(self, serial: str):
        self.d = u2.connect(serial)
        self.serial = serial
        self.adb = AdbDevice(serial)

    def open_app(self):
        current = self.d.app_current()
        if current.get("package") == APP_PACKAGE:
            # Already foreground — skip the stop/start churn (saves ~3-5s per
            # call; open_app is invoked many times per test).
            time.sleep(1)
            self._go_home(timeout=10.0)
            return
        self.d.app_stop(APP_PACKAGE)
        time.sleep(1)
        self.d.app_start(APP_PACKAGE)
        deadline = time.time() + 15.0
        while time.time() < deadline:
            current = self.d.app_current()
            if current.get("package") == APP_PACKAGE:
                break
            time.sleep(1)
        time.sleep(3)
        self._go_home(timeout=15.0)
        current = self.d.app_current()
        if current.get("package") != APP_PACKAGE:
            logger.error("App not in foreground after open_app (current=%s) on %s", current, self.serial)
            self.d.app_start(APP_PACKAGE)
            time.sleep(3)
            self._go_home(timeout=10.0)
        else:
            logger.info("App opened on %s", self.serial)

    def open_settings(self, timeout: float = 20.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                self.d(description="Settings").click(timeout=5)
            except Exception:  # noqa: BLE001 - selector retry
                self._go_home(timeout=8.0)
                time.sleep(1)
                continue
            if self.d(text="Settings").wait(timeout=10.0):
                logger.debug("Settings opened on %s", self.serial)
                return True
            self._go_home(timeout=8.0)
            time.sleep(1)
        # The Settings icon lives on the app's home screen. Get back there and
        # try once more. Use _go_home (not open_app): it only relaunches if the
        # app is genuinely not running, so a transient in-memory SyncState
        # error (e.g. "Wrong passphrase") set by a gating sync survives.
        self._go_home(timeout=15.0)
        if self.d(description="Settings").exists:
            self.d(description="Settings").click(timeout=5)
            if self.d(text="Settings").wait(timeout=10.0):
                logger.debug("Settings opened after returning home on %s", self.serial)
                return True
        logger.error("Settings screen did not open on %s", self.serial)
        return False

    def _adb_tap(self, text: str, timeout: float = 10.0) -> bool:
        """Tap a text element via `adb shell input tap` (more reliable than
        uiautomator2 .click(), which can hit stale/non-rendered nodes).

        Mirrors test_saf_to_drive._adb_tap, which provisioned the reliable
        sync-trigger path.
        """
        return self._adb_tap_selector("text", text, timeout)

    def _adb_tap_desc(self, description: str, timeout: float = 10.0) -> bool:
        return self._adb_tap_selector("description", description, timeout)

    def _adb_tap_selector(self, key: str, value: str, timeout: float = 10.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            el = self.d(**{key: value})
            if el.exists:
                try:
                    info = self.d.jsonrpc.objInfo(el.selector)
                except Exception:  # noqa: BLE001 - selector may be stale
                    time.sleep(0.5)
                    continue
                b = info["bounds"]
                cx = (b["left"] + b["right"]) // 2
                cy = (b["top"] + b["bottom"]) // 2
                self.adb.shell(f"input tap {cx} {cy}")
                time.sleep(0.5)
                return True
            time.sleep(0.5)
        return False

    def trigger_sync(self) -> bool:
        """Trigger a sync, preferring the debug broadcast receiver.

        Sends `am broadcast -a com.picpocket.app.action.SYNC_NOW` (Phase B
        receiver in the debug build) and waits ~8s for a real
        "performSync: starting" logcat line. Returns True when the broadcast
        path worked; otherwise falls back to the UI dance and returns False.
        The broadcast path skips the stop/start + settings navigation that
        cost ~15-25s per sync on older tests.
        """
        # Explicit component (-n), not an implicit broadcast: on Android 8+ a
        # manifest-declared receiver in a targetSdk 34 app is never delivered
        # an implicit broadcast, but an explicit-component broadcast always is.
        watcher = SyncWatcher(self.adb)
        for attempt in range(3):
            self.adb.shell(
                "am broadcast -n com.picpocket.app/.debug.TestSyncTriggerReceiver "
                "-a com.picpocket.app.action.SYNC_NOW"
            )
            try:
                watcher.wait_for_start(timeout=8.0)
                logger.info("Sync triggered via broadcast on %s", self.serial)
                return True
            except TimeoutError:
                # wait_for_start raises when the app refused to start a sync.
                # The common cause is "already syncing": the isSyncing guard
                # rejected the broadcast because another sync is in flight
                # (active reconcile, or a retry-backoff sleep that holds the
                # guard while sleeping). Wait that sync out (the debug
                # receiver resets the backoff, so a re-broadcast runs fresh),
                # then re-broadcast. Any other cause falls through to the UI
                # dance below.
                if watcher.had_sync_denied_but_running():
                    logger.warning(
                        "Sync already in progress on %s (attempt %d); waiting it out and re-triggering",
                        self.serial, attempt + 1,
                    )
                    try:
                        watcher.wait_for_sync(timeout=90.0)
                    except TimeoutError:
                        logger.warning(
                            "In-flight sync did not complete within 90s on %s", self.serial
                        )
                    continue
                break
        logger.warning("Broadcast did not start a sync on %s; falling back to UI", self.serial)
        self._go_home(timeout=15.0)
        if not self.open_settings():
            logger.warning("Settings not reachable on %s", self.serial)
        if not self._adb_tap("Sync", timeout=3.0):
            logger.warning("Sync button not found on %s", self.serial)
        time.sleep(1)
        sync_toggle = self.d(description="Toggle sync")
        if sync_toggle.wait(timeout=3.0):
            hint = self.d(text="Enable sync to start syncing")
            if hint.exists:
                sync_toggle.click()
                time.sleep(2)
                if not hint.exists:
                    logger.info("Sync toggled ON on %s", self.serial)
                else:
                    logger.warning("Sync toggle click did not dismiss hint on %s", self.serial)
        ok = self._adb_tap("Sync Now", timeout=5.0)
        if ok:
            time.sleep(2)
            logger.info("Sync Now tapped (adb) on %s", self.serial)
        else:
            logger.warning("Sync Now button not found on %s", self.serial)
        return False

    def import_pdf(self, pdf_name: str, timeout: float = 30.0):
        # open_app guarantees PicPocket is in front (relaunching if a previous
        # import left it backgrounded); otherwise the Import button lookup
        # silently no-ops and the doc never lands.
        self.open_app()
        import_btn = self.d(description="Import PDF")
        if not import_btn.wait(timeout=5.0):
            logger.warning(
                "Import PDF button not found on %s (current=%s); relaunching",
                self.serial, self.d.app_current(),
            )
            self.open_app()
            import_btn = self.d(description="Import PDF")
        if import_btn.wait(timeout=5.0):
            import_btn.click()
            # Wait for the SAF picker to come up instead of sleeping a fixed 3s
            # (fast when the picker is warm, and no worse when it is cold).
            if not self.d(description="Show roots").wait(timeout=10.0):
                logger.info("SAF picker did not surface 'Show roots' (may already be open)")
            self._navigate_saf(pdf_name, timeout)
            logger.info("PDF '%s' imported on %s", pdf_name, self.serial)
        else:
            logger.warning("Import PDF button not found on %s (current=%s)", self.serial, self.d.app_current())

    def ensure_drive_configured(self, timeout: float = 120.0):
        self.open_app()
        time.sleep(1)
        self.open_settings()
        self.d(text="Sync").click()
        time.sleep(1)
        connect_btn = self.d(text="Select Folder")
        if not connect_btn.wait(timeout=5.0):
            logger.info("Drive sync already configured on %s", self.serial)
            self.d.press("back")
            return
        connect_btn.click()
        time.sleep(2)
        self._select_nextcloud_root(timeout)
        allow_btn = self.d(text="ALLOW")
        if allow_btn.wait(timeout=5.0):
            allow_btn.click()
            time.sleep(2)
        toggle_elem = self.d(description="Toggle sync")
        if toggle_elem.wait(timeout=5.0):
            toggle_elem.click()
            time.sleep(2)
        self._go_home(timeout=10.0)
        logger.info("Drive configured on %s", self.serial)

    def assert_doc_exists(self, title: str, timeout: float = 5.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.d(text=title).exists:
                logger.info("Document '%s' visible on %s", title, self.serial)
                return True
            time.sleep(0.5)
        logger.warning("Document '%s' not found on %s after %.1fs", title, self.serial, timeout)
        return False

    def page_file_exists(self, doc_id: str, filename: str, timeout: float = 20.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            out = self.adb.shell(
                f"run-as {APP_PACKAGE} ls files/documents/{doc_id}/ 2>/dev/null || true",
                timeout=5,
            )
            if filename in (out or "").split():
                logger.info("Page file '%s' present on %s", filename, self.serial)
                return True
            time.sleep(2)
        logger.warning(
            "Page file '%s' not found for doc %s on %s", filename, doc_id, self.serial
        )
        return False

    def page_file_size(self, doc_id: str, filename: str) -> Optional[int]:
        """Return the local size in bytes of a page file, or None if missing.

        Uses `wc -c <file>` (argument form), NOT `wc -c < <file>`: the shell
        redirection is resolved by the outer adb shell (the `shell` user),
        which cannot open the app's private data dir, so run-as never even
        runs. As an argument, the path is resolved under run-as as the app
        user and works.
        """
        out = self.adb.shell(
            f"run-as {APP_PACKAGE} wc -c files/documents/{doc_id}/{filename} 2>/dev/null || true",
            timeout=5,
        )
        out = (out or "").strip()
        if not out:
            return None
        parts = out.split()
        if not parts or not parts[0].isdigit():
            return None
        return int(parts[0])

    def read_device_metadata(self, doc_id: str) -> Optional[dict]:
        """Read the newest metadata.{V}.{P}.json for a doc from device storage."""
        out = self.adb.shell(
            f"run-as {APP_PACKAGE} ls files/documents/{doc_id}/ 2>/dev/null || true",
            timeout=5,
        )
        names = [
            n for n in (out or "").split()
            if re.match(r"metadata\.\d+\.\d+\.json$", n)
        ]
        if not names:
            logger.warning("No versioned metadata for doc %s on %s", doc_id, self.serial)
            return None
        newest = max(names, key=lambda n: int(n.split(".")[1]))
        content = self.adb.shell(
            f"run-as {APP_PACKAGE} cat files/documents/{doc_id}/{newest} 2>/dev/null || true",
            timeout=5,
        )
        if not content:
            logger.warning(
                "Device metadata %s empty for doc %s on %s", newest, doc_id, self.serial
            )
            return None
        try:
            return json.loads(content)
        except json.JSONDecodeError as e:
            logger.warning(
                "Device metadata %s unparseable for %s: %s", newest, doc_id, e
            )
            return None

    def list_local_docs(self) -> list[tuple[str, str]]:
        """Return [(name, doc_id)] for every document in local storage.

        Doc ids are the UUID directory names under files/documents; each
        newest metadata file carries the doc's `name`. Confirms the doc
        really landed in the store, independent of what the home UI renders.
        """
        out = self.adb.shell(
            f"run-as {APP_PACKAGE} ls files/documents/ 2>/dev/null || true",
            timeout=5,
        )
        result = []
        for did in (out or "").split():
            meta = self.read_device_metadata(did)
            if meta and meta.get("name"):
                result.append((meta["name"], did))
        return result

    def find_local_doc(self, name: str) -> Optional[str]:
        """Return the local doc id whose stored name matches, or None."""
        for stored_name, doc_id in self.list_local_docs():
            if stored_name == name:
                return doc_id
        logger.warning("Local doc '%s' not found on %s", name, self.serial)
        return None

    def _go_home(self, timeout: float = 10.0):
        deadline = time.time() + timeout
        back_presses = 0
        while time.time() < deadline:
            current = self.d.app_current()
            if current.get("package") != APP_PACKAGE:
                # Not our app in front. Press back a couple of times to dismiss
                # any system picker/dialog covering it; if it still isn't
                # foreground, relaunch it — it may have been backgrounded or
                # killed between steps (e.g. across the SAF picker), and
                # pressing back into the launcher never recovers.
                if back_presses < 2:
                    self.d.press("back")
                    back_presses += 1
                    time.sleep(1)
                    continue
                logger.warning(
                    "App not foreground in _go_home (current=%s); relaunching on %s",
                    current, self.serial,
                )
                self.d.app_start(APP_PACKAGE)
                time.sleep(3)
                deadline = time.time() + 10.0
                back_presses = 0
                continue
            back_presses = 0
            if self.d(text="PicPocket").exists or self.d(text="No documents yet").exists:
                return
            if self.d(text="PicPocket").wait(timeout=3.0):
                return
            self.d.press("back")
            time.sleep(1)

    def _navigate_saf(self, target_file: str, timeout: float):
        deadline = time.time() + timeout
        roots_opened = False
        while time.time() < deadline:
            if self.d(text=target_file).exists:
                self.d(text=target_file).click()
                time.sleep(3)
                logger.info("SAF: selected file '%s' on %s", target_file, self.serial)
                return
            if roots_opened:
                downloads = self.d(text="Downloads")
                if downloads.exists:
                    downloads.click()
                    time.sleep(3)
                    roots_opened = False
                    continue
            show_roots = self.d(description="Show roots")
            if show_roots.exists:
                show_roots.click()
                roots_opened = True
                time.sleep(2)
                downloads = self.d(text="Downloads")
                if downloads.exists:
                    downloads.click()
                    time.sleep(3)
                    roots_opened = False
            time.sleep(2)
        raise TimeoutError(f"Could not find '{target_file}' in SAF picker")

    def _tap_retry(self, find, description: str, timeout: float = 15.0):
        """Find and tap an element, retrying while the SAF picker UI settles.

        uiautomator2 raises RPCUnknownError/StaleObjectException when a node
        is re-rendered between lookup and tap (e.g. the roots drawer is still
        animating). Re-locate the node each attempt until it settles.
        """
        deadline = time.time() + timeout
        last_err = None
        while time.time() < deadline:
            try:
                el = find()
                if el.exists:
                    el.click(timeout=5)
                    return True
            except Exception as exc:  # noqa: BLE001 - uiautomator stale/RPC variants
                last_err = exc
                time.sleep(0.5)
                continue
            time.sleep(0.5)
        logger.error("SAF: could not tap '%s' on %s: %s", description, self.serial, last_err)
        return False

    def select_saf_folder(self, folder_path: str, timeout: float = 60.0):
        """Select a folder in the SAF picker, handling both navigation states.

        folder_path may be nested ("PicPocketTest/w1"): each segment is entered
        in turn and "USE THIS FOLDER" is only tapped once the breadcrumb shows
        the final segment, so the Nextcloud root itself is never selected.

        Path B (consecutive): picker already open inside the target folder
        (breadcrumb shows it) — "USE THIS FOLDER" is immediately available.
        Path A (first-time): picker opens elsewhere — tap "Show roots", open
        the Nextcloud provider, tap the target folder, confirm with
        "USE THIS FOLDER".
        """
        segments = [s for s in folder_path.split("/") if s]
        final = segments[-1]
        deadline = time.time() + timeout
        breadcrumb_id = "com.google.android.documentsui:id/breadcrumb_text"
        entered_nextcloud = False
        while time.time() < deadline:
            breadcrumb = self.d(resourceId=breadcrumb_id, text=final)
            if breadcrumb.exists:
                if self._tap_retry(lambda: self.d(text="USE THIS FOLDER"),
                                   "USE THIS FOLDER"):
                    time.sleep(2)
                    logger.info("SAF: inside '%s', tapped USE THIS FOLDER on %s",
                                final, self.serial)
                    return

            if not entered_nextcloud:
                if self._tap_retry(lambda: self.d(description="Show roots"), "Show roots"):
                    time.sleep(2)
                    logger.info("SAF: roots shown on %s", self.serial)
                if self._tap_retry(lambda: self.d(text="Nextcloud"), "Nextcloud"):
                    time.sleep(3)
                    entered_nextcloud = True
                    logger.info("SAF: tapped Nextcloud provider on %s", self.serial)

            advanced = False
            for seg in segments:
                if self.d(resourceId=breadcrumb_id, text=seg).exists:
                    continue
                if self.d(text=seg).exists:
                    if self._tap_retry(lambda: self.d(text=seg), seg):
                        time.sleep(3)
                        logger.info("SAF: tapped folder '%s' in Nextcloud on %s",
                                    seg, self.serial)
                        advanced = True
                    break
            if advanced:
                continue

            if entered_nextcloud:
                texts = [tv.get_text() for tv in
                         self.d(className="android.widget.TextView") if tv.get_text()]
                logger.warning(
                    "SAF: '%s' not found after entering Nextcloud on %s — visible texts: %s",
                    final, self.serial, texts,
                )

            time.sleep(2)
        raise TimeoutError(f"Could not select folder '{folder_path}' in SAF picker")

    def _select_nextcloud_root(self, timeout: float):
        self.select_saf_folder(worker_root_folder(), timeout)
