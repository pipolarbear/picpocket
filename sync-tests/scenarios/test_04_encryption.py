import json
import logging
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
from scenarios._integrity import assert_drive_verified

logger = logging.getLogger(__name__)


class TestEncryption:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, reset_state_b, ensure_drive_configured, emu_a, emu_b):
        self.emu_a = emu_a
        self.emu_b = emu_b

    def test_encrypted_drive_blocks_other_device(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices
    ):
        # Device A imports and enables encryption with a passphrase.
        generate_and_push(emu_a.adb, "test-enc", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("test-enc.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)
        assert_drive_verified(oracle, drive_finality=True)

        emu_a.open_settings()
        emu_a.d(text="Sync").click()
        time.sleep(1)
        self._enable_encryption(emu_a, "test-passphrase-123")
        # Enabling encryption launches a sync inside the SyncViewModel scope;
        # triggering another one would navigate home and cancel it ("Job was
        # cancelled"), so wait without re-triggering.
        sync_with_false_mutex_retry(emu_a, watcher_a, trigger=False, timeout=150.0)

        # The shared registry must now report the Drive as encrypted.
        reg = json.loads(oracle.get_file_content("/PicPocketTest/devices.json"))
        assert reg.get("encrypted") is True, (
            f"Server devices.json not marked encrypted: {reg}"
        )

        # Device B (fresh device, unencrypted local state) selects the folder
        # and tries to sync — it must be gated because the Drive is encrypted.
        emu_b.ensure_drive_configured()
        result = wait_for_pattern_with_false_mutex_retry(
            emu_b, watcher_b, ENCRYPTION_GATING
        )
        logger.info("Encryption gating on B: %s", result)

        # The gating error renders on the Sync screen; ensure_drive_configured
        # may leave B on Settings (kept-config fast path), so navigate there.
        emu_b.open_settings()
        emu_b.d(text="Sync").click()
        error_text = emu_b.d(textContains="This Drive is encrypted")
        assert error_text.wait(timeout=10.0), "Encryption error not shown on B"

        # Entering the correct passphrase restores sync (auto-sync again, so
        # wait without re-triggering to avoid cancelling it).
        self._enable_encryption(emu_b, "test-passphrase-123")
        sync_with_false_mutex_retry(emu_b, watcher_b, trigger=False, timeout=150.0)

        # B's first post-passphrase sync can read a stale listing and skip the
        # doc ("undecodable metadata"); re-sync until it actually lands in the
        # local store (ground truth; the home UI can lag a recomposition).
        emu_b._go_home(timeout=10.0)
        assert sync_until(
            emu_b, watcher_b,
            check=lambda: emu_b.find_local_doc("test-enc") is not None,
        ), "Doc not present on B after passphrase"

        # A rotates the passphrase: the re-encrypt sync writes generation 2 to
        # the Drive, which stales B out (B only knows generation 1).
        self._change_passphrase(emu_a, "test-passphrase-456")
        sync_with_false_mutex_retry(emu_a, watcher_a, trigger=False, timeout=150.0)
        assert_drive_verified(oracle, drive_finality=True)

        # B's client bridge lags A's re-encrypt writes by one refresh cycle, so
        # a single sync reads the stale listing (generation 1) and completes as
        # a no-op. Re-trigger until B observes generation 2 and is gated.
        stale = pattern_with_convergence(
            emu_b, watcher_b, WRONG_PASSPHRASE
        )
        logger.info("Stale passphrase gating on B: %s", stale)
        emu_b.open_settings()
        emu_b.d(text="Sync").click()
        # The wrong-passphrase error is a transient in-memory SyncState, not
        # persisted. If it isn't showing yet, trigger one more gating sync while
        # the Sync screen is visible so the error is freshly set.
        error_text = emu_b.d(textContains="Wrong passphrase")
        if not error_text.wait(timeout=5.0):
            emu_b.trigger_sync()
            error_text = emu_b.d(textContains="Wrong passphrase")
        assert error_text.wait(timeout=10.0), "Wrong-passphrase error not shown on B"

        # B adopts the new passphrase and syncs again.
        self._change_passphrase(emu_b, "test-passphrase-456")
        sync_with_false_mutex_retry(emu_b, watcher_b, trigger=False, timeout=150.0)
        emu_b._go_home(timeout=10.0)
        assert sync_until(
            emu_b, watcher_b,
            check=lambda: emu_b.find_local_doc("test-enc") is not None,
        ), "Doc not present on B after P2"

    @staticmethod
    def _enable_encryption(emu, passphrase: str):
        """Open Sync screen and enable encryption via the passphrase dialog.

        The "Enable Encryption" button lives in the Sync screen's Encryption
        section. It opens a "Set Encryption Passphrase" dialog with Passphrase
        + Confirm passphrase fields, confirmed with "Save".

        Fields are filled with set_text (not send_keys): the Compose
        OutlinedTextFields never receive IME keystrokes from uiautomator's
        send_keys, which left them empty and made Save a silent no-op.
        """
        emu.open_settings()
        emu.d(text="Sync").click()
        time.sleep(1)
        btn = emu.d(text="Enable Encryption")
        assert btn.wait(timeout=10.0), "Enable Encryption button not found"
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
            "Encryption did not enable on %s (passphrase fields not populated)" % emu.serial
        )

    @staticmethod
    def _change_passphrase(emu, passphrase: str):
        """Rotate the passphrase via the "Change Passphrase" dialog.

        Same two-field dialog as enabling, confirmed with Save; the dialog only
        opens while encryption is already enabled.
        """
        emu.open_settings()
        emu.d(text="Sync").click()
        time.sleep(1)
        btn = emu.d(text="Change Passphrase")
        assert btn.wait(timeout=10.0), "Change Passphrase button not found"
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
            "Encryption did not stay enabled on %s after change" % emu.serial
        )
