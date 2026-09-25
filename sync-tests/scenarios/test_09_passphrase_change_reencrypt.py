import logging
import re
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import (
    ENCRYPTION_GATING,
    WRONG_PASSPHRASE,
    pattern_with_convergence,
    sync_until,
    sync_with_false_mutex_retry,
    wait_for_pattern_with_false_mutex_retry,
)
from scenarios._integrity import assert_drive_verified, wait_for_metadata

logger = logging.getLogger(__name__)

_METADATA_RE = re.compile(r"metadata\.\d+\.\d+\.json$")


def _first_page_name(files: dict) -> str:
    for name in files:
        if name != ".deleted" and not _METADATA_RE.match(name):
            return name
    return ""


class TestPassphraseChange:
    """Rotating the passphrase must re-encrypt the Drive and stale out peers.

    Device A imports a doc, enables encryption with P1 (server registry becomes
    encrypted, doc metadata passphrase generation 1), then rotates to P2. The
    re-encrypt sync must re-upload every page under the new key (page bytes
    change, metadata passphrase generation bumps to 2). Device B, which knows
    only P1, must then be gated with "stale passphrase generation" until it
    adopts P2.
    """

    @pytest.fixture(autouse=True)
    def setup(self, reset_state, reset_state_b, ensure_drive_configured, emu_a, emu_b):
        self.emu_a = emu_a
        self.emu_b = emu_b

    def test_passphrase_change_reencrypts_and_stales_peer(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices
    ):
        generate_and_push(emu_a.adb, "test-pw", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-pw.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)
        assert_drive_verified(oracle, drive_finality=True)

        doc_prefix = oracle.wait_for_doc_folder()
        assert doc_prefix, "Doc not found in Drive after A's first sync"

        # A enables encryption with P1; the auto-sync re-encrypts generation 1.
        emu_a.open_settings()
        emu_a.d(text="Sync").click()
        time.sleep(1)
        self._enter_passphrase(emu_a, "test-passphrase-123", enable=True)
        sync_with_false_mutex_retry(emu_a, watcher_a, trigger=False, timeout=150.0)
        # The app logs "complete" before the write-back bridge visibly flushes
        # to the server; poll drive finality before reading the metadata so we
        # don't list a not-yet-flushed folder.
        assert_drive_verified(oracle, drive_finality=True)

        metadata = wait_for_metadata(oracle, doc_prefix)
        assert metadata is not None, "No versioned metadata on server after enabling encryption"
        meta, version, passphrase = metadata
        assert meta is None, "Metadata should be encrypted on server"
        assert passphrase == 1, f"Expected metadata generation 1, got {passphrase}"
        files1 = oracle.list_folder_with_lengths(f"/PicPocketTest/{doc_prefix}")
        page_name = _first_page_name(files1)
        assert page_name, "No page file in doc folder after generation 1"
        p1_bytes = oracle.get_file_content(f"/PicPocketTest/{doc_prefix}/{page_name}")

        # B knows P1 too: configure, pass the encryption gate, sync cleanly.
        emu_b.ensure_drive_configured()
        result = wait_for_pattern_with_false_mutex_retry(
            emu_b, watcher_b, ENCRYPTION_GATING
        )
        logger.info("Encryption gating on B: %s", result)
        emu_b.open_settings()
        emu_b.d(text="Sync").click()
        error_text = emu_b.d(textContains="This Drive is encrypted")
        assert error_text.wait(timeout=10.0), "Encryption error not shown on B"
        self._enter_passphrase(emu_b, "test-passphrase-123", enable=True)
        sync_with_false_mutex_retry(emu_b, watcher_b, trigger=False, timeout=150.0)
        emu_b._go_home(timeout=10.0)
        assert sync_until(
            emu_b, watcher_b,
            check=lambda: emu_b.find_local_doc("test-pw") is not None,
        ), "Doc not present on B with P1"

        # A rotates to P2: the auto-sync re-encrypts every page (new key ->
        # different ciphertext) and bumps the metadata generation to 2.
        emu_a.open_settings()
        emu_a.d(text="Sync").click()
        time.sleep(1)
        self._enter_passphrase(emu_a, "test-passphrase-456", enable=False)
        sync_with_false_mutex_retry(emu_a, watcher_a, trigger=False, timeout=150.0)
        assert_drive_verified(oracle, drive_finality=True)

        metadata2 = wait_for_metadata(oracle, doc_prefix)
        assert metadata2 is not None, "No versioned metadata on server after rotation"
        _, _, passphrase2 = metadata2
        assert passphrase2 == 2, f"Expected metadata generation 2, got {passphrase2}"
        p2_bytes = oracle.get_file_content(f"/PicPocketTest/{doc_prefix}/{page_name}")
        assert p2_bytes != p1_bytes, (
            "Page bytes unchanged after passphrase change (no re-encrypt)"
        )

        # B only knows P1; the remote generation (2) now exceeds B's local
        # count (1), so B is gated as stale and shows the wrong-passphrase
        # error instead of syncing. B's client bridge lags A's writes by one
        # refresh cycle, so re-trigger until it observes generation 2.
        stale = pattern_with_convergence(
            emu_b, watcher_b, WRONG_PASSPHRASE
        )
        logger.info("Stale passphrase gating on B: %s", stale)
        emu_b.open_settings()
        emu_b.d(text="Sync").click()
        error_text = emu_b.d(textContains="Wrong passphrase")
        assert error_text.wait(timeout=10.0), "Wrong-passphrase error not shown on B"

        # B adopts P2 and syncs again.
        self._enter_passphrase(emu_b, "test-passphrase-456", enable=False)
        sync_with_false_mutex_retry(emu_b, watcher_b, trigger=False, timeout=150.0)
        emu_b._go_home(timeout=10.0)
        assert sync_until(
            emu_b, watcher_b,
            check=lambda: emu_b.find_local_doc("test-pw") is not None,
        ), "Doc not present on B after P2"
        assert_drive_verified(oracle, drive_finality=True)

    @staticmethod
    def _enter_passphrase(emu, passphrase: str, enable: bool):
        """Open the passphrase dialog and confirm it.

        enable=True taps the "Enable Encryption" button (fresh enable, dialog
        title "Set Encryption Passphrase"); enable=False taps "Change
        Passphrase" (dialog title "Change Passphrase"). Both dialogs expose two
        EditText fields (Passphrase + Confirm passphrase) confirmed with Save.
        Fields are filled with set_text: Compose OutlinedTextFields never
        receive IME keystrokes from uiautomator's send_keys.
        """
        emu.open_settings()
        emu.d(text="Sync").click()
        time.sleep(1)
        label = "Enable Encryption" if enable else "Change Passphrase"
        btn = emu.d(text=label)
        assert btn.wait(timeout=10.0), f"{label} button not found"
        btn.click()
        time.sleep(1)

        fields = emu.d(className="android.widget.EditText")
        assert len(fields) >= 2, "Passphrase dialog fields not found"
        fields[0].set_text(passphrase)
        fields[1].set_text(passphrase)
        save = emu.d(text="Save")
        assert save.wait(timeout=5.0), "Save button not found in passphrase dialog"
        save.click()
        time.sleep(2)

        encrypted_text = emu.d(text="Drive files are encrypted at rest")
        assert encrypted_text.wait(timeout=10.0), (
            f"Encryption did not stay enabled on {emu.serial} after {label}"
        )