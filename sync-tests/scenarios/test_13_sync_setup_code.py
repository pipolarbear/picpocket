import logging
import time

import pytest

from devices.pdf_utils import generate_and_push
from devices.tracing import sync_until, sync_with_false_mutex_retry
from scenarios._integrity import assert_drive_verified
from scenarios import test_04_encryption as enc

logger = logging.getLogger(__name__)


class TestSyncSetupCode:
    @pytest.fixture(autouse=True)
    def setup(self, reset_state, reset_state_b, ensure_drive_configured, emu_a, emu_b):
        self.emu_a = emu_a
        self.emu_b = emu_b

    def test_fresh_device_joins_after_rotation_via_setup_code(
        self, emu_a, emu_b, watcher_a, watcher_b, oracle, two_devices
    ):
        # Device A: import, sync, enable encryption (generation 1), then rotate
        # to generation 2. A fresh device entering the correct passphrase would
        # previously be rejected as if it were wrong; the setup code carries the
        # generation so it joins.
        generate_and_push(emu_a.adb, "setup-code", pages=1)
        emu_a.open_app()
        emu_a.import_pdf("setup-code.pdf")
        time.sleep(3)
        sync_with_false_mutex_retry(emu_a, watcher_a)
        assert_drive_verified(oracle, drive_finality=True)

        enc.TestEncryption._enable_encryption(emu_a, "test-passphrase-123")
        sync_with_false_mutex_retry(emu_a, watcher_a, trigger=False, timeout=150.0)

        enc.TestEncryption._change_passphrase(emu_a, "test-passphrase-456")
        sync_with_false_mutex_retry(emu_a, watcher_a, trigger=False, timeout=150.0)
        assert_drive_verified(oracle, drive_finality=True)

        # A shares its sync setup as a code.
        code = _open_share_and_read_code(emu_a)
        assert code.startswith("picpocket-sync:"), f"unexpected setup code: {code[:40]}"

        # B is a fresh device (no folder, no passphrase). Applying the pasted
        # setup code configures the folder and the passphrase+generation, then
        # syncs — it must succeed.
        _apply_setup_code(emu_b, code)

        emu_b._go_home(timeout=10.0)
        assert sync_until(
            emu_b, watcher_b,
            check=lambda: emu_b.find_local_doc("setup-code") is not None,
        ), "Doc not present on B after applying the setup code"


def _open_share_and_read_code(emu) -> str:
    emu.open_settings()
    emu.d(text="Sync").click()
    time.sleep(1)
    entry = emu.d(text="Share or scan setup")
    assert entry.wait(timeout=10.0), "Sync setup entry not found"
    entry.click()
    time.sleep(1)
    share = emu.d(text="Share setup")
    assert share.wait(timeout=10.0), "Share setup button not found"
    share.click()
    time.sleep(1)
    code = emu.d(textStartsWith="picpocket-sync:")
    assert code.wait(timeout=10.0), "Setup code not shown"
    return code.get_text()


def _apply_setup_code(emu, code: str) -> None:
    emu.open_settings()
    emu.d(text="Sync").click()
    time.sleep(1)
    entry = emu.d(text="Share or scan setup")
    assert entry.wait(timeout=10.0), "Sync setup entry not found on B"
    entry.click()
    # Wait for the setup screen and its paste field to compose.
    assert emu.d(text="Join another device").wait(timeout=10.0), "Sync setup screen not shown on B"
    fields = None
    deadline = time.time() + 10.0
    while time.time() < deadline:
        els = emu.d(className="android.widget.EditText")
        if len(els) >= 1:
            fields = els
            break
        time.sleep(0.5)
    assert fields and len(fields) >= 1, "Setup code field not found"
    fields[0].set_text(code)
    apply_btn = emu.d(text="Apply")
    assert apply_btn.wait(timeout=5.0), "Apply button not found"
    apply_btn.click()
    time.sleep(2)

    # Applying launches the SAF picker (pre-navigated to the shared folder);
    # the user still confirms the folder to grant access.
    emu.select_saf_folder("PicPocketTest")
    allow = emu.d(text="ALLOW")
    if allow.wait(timeout=5.0):
        allow.click()
        time.sleep(2)
    logger.info("Applied setup code on %s", emu.serial)
