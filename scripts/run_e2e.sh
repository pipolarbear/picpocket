#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/sync-tests"

TS="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$ROOT/sync-tests/tmp"

# `--wait` anywhere in the args makes the launcher block until the pytest
# session(s) finish; it is stripped before anything is forwarded to pytest.
wait_mode=0
_rest=()
for _a in "$@"; do
  if [ "$_a" = "--wait" ]; then
    wait_mode=1
  else
    _rest+=("$_a")
  fi
done

mode="${_rest[0]:-serial}"
if [ "${#_rest[@]}" -gt 0 ]; then
  set -- "${_rest[@]:1}"
else
  set --
fi

if [ "$mode" = "parallel" ]; then
  # Phase C: two workers, one emulator slot each (5554/w1 and 5556/w2),
  # splitting the SINGLE-device suite. Two-device tests (test_01::test_b,
  # test_04, test_05, test_09, test_10) must run via 'run_e2e.sh serial'
  # afterwards: they need both emulators in one session and would collide
  # with a sibling worker (boot_device_b refuses them under NEXTCLOUD_SUBDIR).
  w1=( "scenarios/test_01_happy_path.py::TestHappyPath::test_a_imports_3page_pdf_drive_verifies"
       "scenarios/test_saf_to_drive.py"
       "scenarios/test_06_push_after_local_edit.py"
       "scenarios/test_12_interrupted_sync.py"
       "scenarios/test_14_native_pdf_prune.py" )
  w2=( "scenarios/test_02_remote_add_page.py"
       "scenarios/test_03_conflict.py"
       "scenarios/test_07_corrupt_registry_halts.py"
       "scenarios/test_08_noop_resync.py"
       "scenarios/test_11_offline_sync.py"
       "scenarios/test_01_happy_path.py::TestHappyPath::test_batch_imports_multiple_docs_single_sync" )
  pids=()
  for i in 1 2; do
    log="$ROOT/sync-tests/tmp/worker-$i-$TS.log"
    pidf="$ROOT/sync-tests/tmp/worker-$i-$TS.pid"
    if [ "$i" = "1" ]; then
      nohup env EMULATOR_SERIAL=emulator-5554 NEXTCLOUD_SUBDIR=w1 \
        python3 -m pytest "${w1[@]}" -v >"$log" 2>&1 &
    else
      nohup env EMULATOR_SERIAL=emulator-5556 NEXTCLOUD_SUBDIR=w2 \
        python3 -m pytest "${w2[@]}" -v >"$log" 2>&1 &
    fi
    pids+=($!)
    echo $! > "$pidf"
    echo "worker-$i: pid=$! log=$log"
  done
  if [ "$wait_mode" = "1" ]; then
    rc=0
    for p in "${pids[@]}"; do
      wait "$p" || rc=1
    done
    if [ "$rc" != "0" ]; then
      echo "one or more parallel workers FAILED (logs in $ROOT/sync-tests/tmp/)" >&2
      exit 1
    fi
    echo "parallel workers finished; logs in $ROOT/sync-tests/tmp/"
  fi
  exit 0
fi

# serial (default): full gate on the default worker (no NEXTCLOUD_SUBDIR,
# rooted at PicPocketTest itself). Pass test paths as "$@".
LOG="$ROOT/sync-tests/tmp/run-$TS.log"
PID_FILE="$ROOT/sync-tests/tmp/run-$TS.pid"

nohup python3 -m pytest "$@" -v >"$LOG" 2>&1 &
PID=$!
echo "$PID" >"$PID_FILE"

echo "log: $LOG"
echo "pid: $PID"

if [ "$wait_mode" = "1" ]; then
  wait "$PID"
  echo "serial run finished: $LOG"
fi