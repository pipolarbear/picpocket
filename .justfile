# Justfile for PicPocket Android Project
# Build, test, and drive the sync layers.
#
# All test tiers are orchestrated by the unified runner (scripts/test_runner.py):
#   just test                — run every tier in dependency order (gated)
#   just test-failed         — rerun only what failed in the last run
#   just test-name <pat>     — run only tests matching <pat>
#   just test-group <x>      — run only a group: unit|instrumented|infra|saf|scenario
#   just test-v              — same as `just test` but streams raw output
#   just test-dry-run        — show the plan + ETA without running anything
#
# Tier dependency rules (enforced by the runner):
#   unit → (nothing)          infra → (nothing)
#   instrumented → infra      (SAFWriteProbeTest needs the stack + SAF grant)
#   saf → infra               scenario → saf + infra
# A tier is skipped when a dependency failed (recorded for --group, fresh for --all).
#
# SAFWriteProbeTest is a host-driven diagnostic probe (not a regression test):
# its @Test methods log timestamped traces that sync-tests/_probe_*.py correlate
# against server-side WebDAV ground truth. It is excluded from the instrumented
# tier sweep and run explicitly by the probe scripts with the SAF grant set up.

rclone_mount := "./tmp/gdrive-test"
compose_file := "./sync-tests/docker-compose.test.yml"
avd_name := "testPixel7"
sdk_root := env_var_or_default("ANDROID_HOME", env_var_or_default("ANDROID_SDK_ROOT", "/home/zun/.local/android-sdk"))

APP_NAME := "com.picpocket.app"
APK_PATH := "app/build/outputs/apk/debug/app-debug.apk"

_default: help

# List all available tasks (descriptions from the comment above each task)
help:
    @just --list

# Build the debug APK
build:
    @echo "Building..."
    ./gradlew assembleDebug
    @echo "Build completed!"

# Run the JVM unit tests (Robolectric) via the unified runner.
unit-test *args:
    @echo "Running unit tests..."
    python scripts/test_runner.py --group unit --args "{{args}}"
    @echo "Tests completed!"

# Run instrumented Android tests (Compose, androidTest) on one emulator
# (testPixel7, emulator-5554). Always reboots from the sync_test_ready snapshot
# for a known state; runs connectedDebugAndroidTest only on that device.
# Depends on infra: SAFWriteProbeTest needs the Nextcloud stack + SAF grant
# (skipped when infra is recorded-failed; --force overrides).
# Extra gradle args (e.g. -Pandroid.testInstrumentationRunnerArguments.class=...)
# are forwarded via --args.
android-test *args:
    python scripts/test_runner.py --group instrumented --args "{{args}}"

# Build and install the debug APK on the connected device
install: build
    adb -d install -r {{APK_PATH}}
    @echo "Installed successfully!"

# Uninstall the app from the connected device
uninstall:
    adb uninstall {{APP_NAME}}
    @echo "Uninstalled!"

# Run Android lint
lint:
    ./gradlew lint

# Remove build artifacts
clean:
    rm -rf app/build .gradle
    @echo "Cleaned!"

# One-time setup of the testPixel7 AVD + system image (requires sdkmanager + avdmanager)
setup-emulator:
    @echo "Setting up emulator..."
    {{sdk_root}}/cmdline-tools/latest/bin/sdkmanager --sdk_root={{sdk_root}} "system-images;android-34;google_apis;x86_64"
    echo "no" | {{sdk_root}}/cmdline-tools/latest/bin/avdmanager create avd -n testPixel7 -k "system-images;android-34;google_apis;x86_64" -d pixel_7
    @echo "Emulator ready! Use 'just scenario-test' to run the full sync suite."

# Create the second AVD (testPixel7b) for two-device sync scenarios. Same
# system image + device definition as testPixel7, its own snapshot store.
setup-emulator-b:
    @echo "Setting up second emulator AVD (testPixel7b)..."
    echo "no" | {{sdk_root}}/cmdline-tools/latest/bin/avdmanager create avd -n testPixel7b -k "system-images;android-34;google_apis;x86_64" -d pixel_7
    @echo "testPixel7b ready! Bake its snapshot with 'just create-snapshot -- --avd testPixel7b --port 5556'."

# Bake a configured snapshot for an AVD (Nextcloud account + PIN 1234).
# Defaults bake testPixel7 on port 5554. For the second device:
#   just create-snapshot -- --avd testPixel7b --port 5556
# One-time provisioning step; not part of scenario-test.
create-snapshot *args:
    python sync-tests/scripts/create_snapshot.py {{args}}

# Delete the testPixel7 AVD and its system image
cleanup-emulator:
    @echo "Cleaning up..."
    {{sdk_root}}/cmdline-tools/latest/bin/avdmanager delete avd -n testPixel7 || true
    yes | {{sdk_root}}/cmdline-tools/latest/bin/sdkmanager --sdk_root={{sdk_root}} --uninstall "system-images;android-34;google_apis;x86_64" || true
    @echo "Cleaned!"

# Start the Nextcloud + rclone test stack in the background (detached).
# Required by all test tasks; the suites also auto-start it via fixtures.
up:
    @docker compose -f {{compose_file}} up -d >/dev/null

# Full teardown of the test environment: compose down -v, purge Drive + rclone
# mount, wipe Nextcloud data, caches and lock files, and kill the emulators. Irreversible.
sync-clean:
    docker compose -f {{compose_file}} down -v --rmi all --remove-orphans
    fusermount -uz {{rclone_mount}} || true
    umount -l {{rclone_mount}} || true
    rm -rf {{rclone_mount}}
    rclone purge gtest:PicPocketTest || true
    docker run --rm -v /home/zun/dev/oc/pdfscanner/tmp:/tmp alpine sh -c "rm -rf /tmp/nc_data" 2>/dev/null || true
    rm -rf tmp/rclone-vfs-cache
    rm -f tmp/.rclone_mount.lock tmp/.nextcloud_stack.lock
    python sync-tests/scripts/kill_emulator.py {{avd_name}} || true

# Clear rclone's on-disk vfs/vfsMeta cache remnants before a scenario run.
# The persistent cache can carry stale path-to-Drive-ID mappings after a purge
# or folder-ID change, which makes Nextcloud's mount-backed GETs 404 on files
# that exist. Only the cache is removed — the mount, stack and emulators stay
# up (the running daemon recreates cache entries on demand), so a re-run keeps
# its context and never needs a full sync-clean.
clear-rclone-cache:
    rm -rf tmp/rclone-vfs-cache

# ---------------------------------------------------------------------------
# Unified test runner — the single entry point for all test tiers.
# ---------------------------------------------------------------------------

# Every test run holds a systemd inhibitor (idle/sleep/shutdown/lid) so a host
# screen-lock auto-suspend can't freeze the emulators mid-run: a suspend makes
# the guest clock jump on resume, firing a burst of ANRs whose modal dialog
# then blocks all remaining UI tests.
inhibit := "systemd-inhibit --what=idle:sleep:shutdown:handle-lid-switch --"

# Run every test tier in dependency order: unit → infra → instrumented → saf →
# scenario. Tiers whose dependency failed in the same run are skipped (e.g.
# scenario is not executed when saf or infra failed). Extra args are forwarded
# to the runner (e.g. --name <pattern>). Use -v for streaming output.
test *args:
    {{inhibit}} python scripts/test_runner.py {{args}}

# Run every tier (equivalent to `just test` with no args).
test-all:
    {{inhibit}} python scripts/test_runner.py

# Rerun only the tests that failed in the last recorded run. Gradle tiers are
# filtered by failed class (from JUnit XML), pytest tiers use --lf. The same
# dependency gating applies (a tier whose dependency failed is skipped).
test-failed *args:
    {{inhibit}} python scripts/test_runner.py --failed {{args}}

# Run only the tests whose name contains <pattern> (e.g. "test_09").
test-name +pattern:
    {{inhibit}} python scripts/test_runner.py --name {{pattern}}

# Run only the given group(s), comma-separated:
#   unit | instrumented | infra | saf | scenario
# Dependencies are consulted in the results DB: a recorded FAILED dependency
# skips the tier (override with --force); no record warns and proceeds.
test-group +groups:
    {{inhibit}} python scripts/test_runner.py --group {{groups}}

# Stream all subprocess output live (detailed run), otherwise bars + summary.
test-v *args:
    {{inhibit}} python scripts/test_runner.py -v {{args}}

# Show the tier plan, dependency edges, estimated durations and overall ETA
# without executing anything.
test-dry-run:
    python scripts/test_runner.py --dry-run

# ---------------------------------------------------------------------------
# Lower-level recipes (also routed through the unified runner).
# ---------------------------------------------------------------------------

# STORAGE LAYER: rclone mount ↔ Drive and WebDAV ↔ rclone ↔ Drive.
# Runs sync-tests/infra_tests/ (fuse mount, webdav, cross-layer, edge cases)
# against the Nextcloud + rclone stack only — no emulator, no app.
# Used to gain confidence in the storage primitives.
# Extra args (e.g. -k <expr>, --maxfail=1) are forwarded to pytest via --args.
infra-test *args:
    {{inhibit}} python scripts/test_runner.py --group infra --args "{{args}}"

# FOCUSED SAF CHAIN: scenarios/test_saf_to_drive.py (4 tests: folder select,
# small/large file, reinstall). A fast single-device SUBSET of scenario-single
# (which already includes these tests) kept for quick iteration on the
# SAF -> WebDAV -> rclone -> Drive chain. ~10 min.
# Extra args (e.g. --no-reset, --maxfail=1) are forwarded to pytest via --args.
saf-test *args:
    {{inhibit}} python scripts/test_runner.py --group saf --args "{{args}}"

# SINGLE-DEVICE sync scenarios, 2-way parallel.
# Builds the APK, boots BOTH emulators, and runs the 13 single-device tests
# concurrently via scripts/run_e2e.sh parallel: worker-1 (5554/w1) runs
# test_01::test_a + the 4 SAF tests + push-after-edit (test_06) + interrupted
# sync (test_12), worker-2 (5556/w2) runs test_02 + test_03 + corrupt registry
# (test_07) + noop resync (test_08) + offline sync (test_11) + the batch
# import (test_01::test_batch). The five two-device scenarios (test_b,
# test_04, test_05, test_09, test_10) are NOT included: they need both
# emulators in one session, so they run under scenario-two-device. Launches
# both workers in the background and returns immediately; pass --wait to block
# until both workers have finished.
scenario-single *args: clear-rclone-cache
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    bash scripts/run_e2e.sh parallel {{args}}

# TWO-DEVICE sync scenarios, serial.
# Builds the APK, boots BOTH emulators, and runs the five scenarios that need
# both devices in one session (rooted at PicPocketTest): test_01::test_b
# (download from another device), test_04 (encryption bootstrapping),
# test_05 (orphan), test_09 (passphrase rotation), test_10 (mutex contention).
# Complements scenario-single: together they cover every scenario exactly once.
# Launches pytest in the background and returns immediately; pass --wait to
# block until the session has finished.
scenario-two-device *args: clear-rclone-cache
    ./gradlew assembleDebug
    python sync-tests/scripts/ensure_emulator.py
    bash scripts/run_e2e.sh serial \
        scenarios/test_01_happy_path.py::TestHappyPath::test_b_downloads_from_other_device \
        scenarios/test_04_encryption.py \
        scenarios/test_05_orphan.py \
        scenarios/test_09_passphrase_change_reencrypt.py \
        scenarios/test_10_contention.py \
        {{args}}

# FULL SYNC SUITE via the unified runner: the scenario tier runs
# scenario-single (13 single-device tests, 2 parallel workers) then
# scenario-two-device (5 two-device tests serial), in that order — the
# two-device stage needs both emulators, which the parallel workers occupy.
# Non-overlapping coverage of all 18 scenarios. Gated on saf + infra passing.
# Extra pytest args are forwarded via --args.
scenario-test *args:
    python scripts/test_runner.py --group scenario --args "{{args}}"

# Run both primitive layers in sequence via the runner: infra first (stack
# only), then saf (emulator + app). A confidence pass over the storage chain;
# saf is skipped if infra failed in the same run.
chain-test:
    python scripts/test_runner.py --group infra,saf