#!/usr/bin/env python3
"""Unified test runner for PicPocket.

Orchestrates all five test tiers (JVM unit, instrumented, infra, saf, e2e
scenarios) behind one CLI with:

  - dependency-aware gating  (a tier is skipped when a dependency failed)
  - rerun-failed support     (--failed)
  - selection by name        (--name) and by group (--group)
  - live progress + ETA      (rich) and a verbose/detailed mode (-v)
  - persisted results DB     (tmp/test-results.json) used for gating, ETA
                             history and rerun-failed

Tier dependency model (Option C, hybrid):
  - `--all` (default): tiers run in topological order; dependencies execute
    fresh in the same invocation and a FAILED dep gates dependents.
  - `--group X`: dependencies are consulted in the results DB instead. A
    recorded FAILED dep skips the tier (override with --force); a missing or
    passing record warns and proceeds.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from xml.etree import ElementTree

from rich.console import Console
from rich.progress import (
    BarColumn,
    Progress,
    ProgressBar,
    ProgressColumn,
    SpinnerColumn,
    TextColumn,
    TimeElapsedColumn,
)
from rich.table import Column as TableColumn, Table
from rich.text import Text

# Bar colors: running bars are green while the tier has no failures, and flip
# to red/magenta the moment a test fails. Finished bars are green on success,
# red on failure. The color is driven by task.fields["failed"] (0 or >0).
BAR_OK = "rgb(114,156,31)"
BAR_ERR = "rgb(249,38,114)"


class OutcomeBarColumn(BarColumn):
    """Bar whose fill color follows the task's failure count."""

    def render(self, task) -> ProgressBar:
        failed = int(task.fields.get("failed", 0) or 0)
        is_finished = task.total is not None and task.completed >= task.total
        fill = BAR_ERR if failed > 0 else BAR_OK
        return ProgressBar(
            total=max(0, task.total) if task.total is not None else None,
            completed=max(0, task.completed),
            width=None if self.bar_width is None else max(1, self.bar_width),
            pulse=not task.started,
            animation_time=task.get_time(),
            style=self.style,
            complete_style=fill,
            finished_style=BAR_ERR if failed > 0 and is_finished else BAR_OK,
            pulse_style=BAR_OK,
        )


class TaskETA(ProgressColumn):
    """ETA field whose meaning depends on task state.

    Running task -> "ETA  HH:MM:SS": the estimated remaining time for THIS
    task (recorded average duration minus elapsed, falling back to a live
    progress-rate estimate). Finished task -> "TETA HH:MM:SS": the frozen
    estimate of how long the whole suite still has to run from the moment
    that task completed. Both render "--:--:--" when no estimate is possible.
    """

    def __init__(self, db: dict, remaining: list, snapshots: dict):
        self.db = db
        self.remaining = remaining
        self.snapshots = snapshots
        super().__init__(TableColumn(min_width=13, justify="right"))

    def render(self, task) -> Text:
        finished = task.total is not None and task.completed >= task.total
        if finished:
            tier = task.fields.get("tier")
            eta = self.snapshots.get(tier)
            if eta is None:
                eta = suite_eta_seconds(self.db, self.remaining)
            return Text(f"TETA {format_duration(eta)}", no_wrap=True)
        eta = self._task_remaining(task)
        return Text(f"ETA {format_duration(eta)}", no_wrap=True)

    def _task_remaining(self, task) -> float | None:
        """Estimated remaining seconds for the running task."""
        tier = task.fields.get("tier")
        elapsed = (task.get_time() - task.start_time) if task.started else 0.0
        if tier:
            avg = avg_duration(self.db, tier)
            if avg is not None:
                return max(0.0, avg - elapsed)
        if task.total and task.completed and elapsed > 0:
            rate = task.completed / elapsed
            return max(0.0, (task.total - task.completed) / rate)
        return None

ROOT = Path(__file__).resolve().parent.parent
SYNC_TESTS = ROOT / "sync-tests"
VENV_PYTHON = SYNC_TESTS / ".venv" / "bin" / "python"
DB_PATH = ROOT / "tmp" / "test-results.json"
LOG_DIR = ROOT / "tmp" / "test-logs"
GRADLEW = ROOT / "gradlew"
UNIT_XML_DIR = ROOT / "app" / "build" / "test-results" / "testDebugUnitTest"
INSTRUMENTED_XML_DIR = ROOT / "app" / "build" / "outputs" / "androidTest-results" / "connected" / "debug"
EMULATOR_SCRIPT = SYNC_TESTS / "scripts" / "ensure_emulator.py"

EMULATOR_A = "emulator-5554"
APK_OUT = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

# SAFWriteProbeTest is a host-driven diagnostic instrument, not a regression
# test: its @Test methods log a timestamped trace that the probe scripts
# (_probe_saf_timeline.py, _probe_lock_exclusive.py) correlate against
# server-side WebDAV ground truth. It requires a persisted SAF tree grant that
# only those scripts establish, so it is excluded from the instrumented tier
# sweep and run explicitly by the probe harness instead.
EXCLUDED_INSTRUMENTED_CLASSES = ["com.picpocket.app.drive.SAFWriteProbeTest"]

console = Console()

# ---------------------------------------------------------------------------
# Tier manifest
# ---------------------------------------------------------------------------

# scenario serial phase: the five two-device scenarios (rooted at PicPocketTest)
SCENARIO_SERIAL_ARGS = [
    "scenarios/test_01_happy_path.py::TestHappyPath::test_b_downloads_from_other_device",
    "scenarios/test_04_encryption.py",
    "scenarios/test_05_orphan.py",
    "scenarios/test_09_passphrase_change_reencrypt.py",
    "scenarios/test_10_contention.py",
]

# scenario parallel phase: worker-1 (5554/w1) vs worker-2 (5556/w2)
SCENARIO_WORKERS = {
    "w1": ("emulator-5554", [
        "scenarios/test_01_happy_path.py::TestHappyPath::test_a_imports_3page_pdf_drive_verifies",
        "scenarios/test_saf_to_drive.py",
        "scenarios/test_06_push_after_local_edit.py",
        "scenarios/test_12_interrupted_sync.py",
    ]),
    "w2": ("emulator-5556", [
        "scenarios/test_02_remote_add_page.py",
        "scenarios/test_03_conflict.py",
        "scenarios/test_07_corrupt_registry_halts.py",
        "scenarios/test_08_noop_resync.py",
        "scenarios/test_11_offline_sync.py",
        "scenarios/test_01_happy_path.py::TestHappyPath::test_batch_imports_multiple_docs_single_sync",
    ]),
}

# Expected test counts per tier (used only to size the progress bars).
EXPECTED_COUNTS = {
    "unit": None,          # parsed from JUnit XML after the batch run
    "instrumented": None,  # parsed from JUnit XML after the batch run
    "infra": None,         # collected with pytest --collect-only
    "saf": 4,
    "scenario": 13 + 5,    # parallel workers (7 + 6) + serial phase (5)
}

TIERS = {
    "unit": {
        "runner": "gradle_unit",
        "deps": [],
        "emulator": None,
        "build_apk": False,
        "description": "JVM unit tests (Robolectric)",
    },
    "instrumented": {
        "runner": "gradle_instrumented",
        "deps": ["infra"],   # SAFWriteProbeTest needs the Nextcloud stack + SAF grant
        "emulator": "single",
        "build_apk": False,
        "description": "Instrumented tests (Compose, androidTest)",
        "note": "SAFWriteProbeTest excluded: host-driven probe, run via "
                "sync-tests/_probe_*.py with the SAF grant established",
    },
    "infra": {
        "runner": "pytest",
        "deps": [],
        "emulator": None,
        "build_apk": False,
        "pytest_args": ["infra_tests/"],
        "inhibit_sleep": False,
        "description": "Storage chain: rclone mount, WebDAV, cross-layer",
    },
    "saf": {
        "runner": "pytest",
        "deps": ["infra"],
        "emulator": "single",
        "build_apk": True,
        "pytest_args": ["scenarios/test_saf_to_drive.py"],
        "inhibit_sleep": True,
        "description": "SAF -> WebDAV -> rclone -> Drive chain (fast subset)",
    },
    "scenario": {
        "runner": "scenario",
        "deps": ["saf", "infra"],
        "emulator": "both",
        "build_apk": True,
        "inhibit_sleep": True,
        "description": "Full sync scenarios (single- + two-device)",
    },
}

ORDER = ["unit", "infra", "instrumented", "saf", "scenario"]


# ---------------------------------------------------------------------------
# Results DB
# ---------------------------------------------------------------------------

def load_db() -> dict:
    if DB_PATH.exists():
        try:
            return json.loads(DB_PATH.read_text())
        except json.JSONDecodeError:
            console.print("[yellow]WARN[/] results DB unreadable, starting fresh")
    return {"schema": 1, "tiers": {}}


def save_db(db: dict) -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    DB_PATH.write_text(json.dumps(db, indent=2))


def record_tier(db: dict, tier: str, status: str, duration: float,
                items: dict[str, str] | None = None) -> None:
    entry = db["tiers"].setdefault(tier, {"durations": []})
    entry["status"] = status
    entry["duration"] = round(duration, 1)
    entry["ts"] = int(time.time())
    if items is not None:
        entry["items"] = items
    # keep the last three durations for ETA estimation
    entry["durations"] = (entry.get("durations") or [])[-2:] + [round(duration, 1)]


def tier_dep_status(db: dict, tier: str) -> dict[str, str]:
    """Recorded status of each dependency of `tier` (or {} when absent)."""
    deps = TIERS[tier]["deps"]
    return {d: db["tiers"].get(d, {}).get("status") for d in deps}


def avg_duration(db: dict, tier: str) -> float | None:
    durations = db["tiers"].get(tier, {}).get("durations") or []
    return sum(durations) / len(durations) if durations else None


# ---------------------------------------------------------------------------
# Subprocess helpers
# ---------------------------------------------------------------------------

# Operations to inhibit while tests run. `sleep` and `idle` block the desktop
# from suspending/locking-to-sleep mid-run; `shutdown` and `handle-lid-switch`
# cover manual shutdown / laptop lid close. Without this, a host suspend freezes
# the emulators and the guest's clock jump fires a burst of Android ANRs on
# resume (e.g. the Nextcloud bridge's "Timed out while trying to bind"), whose
# modal dialog then blocks every UI interaction in the remaining tests.
INHIBIT_WHAT = "idle:sleep:shutdown:handle-lid-switch"


def _inhibit_cmd(cmd: list[str]) -> list[str]:
    """Prepend systemd-inhibit so the child holds a sleep/idle inhibitor."""
    return ["systemd-inhibit", f"--what={INHIBIT_WHAT}", "--", *cmd]


def run(cmd: list[str], cwd: Path, log_path: Path, verbose: bool,
        env: dict | None = None, line_cb=None, inhibit: bool = False) -> int:
    """Run a command, teeing output to a log file.

    In verbose mode the output is also streamed to the console. `line_cb`
    (optional) receives each decoded line for live progress parsing.

    Logs are flushed line-by-line so a long-running phase (e.g. the serial
    scenario batch) is visible in real time instead of only when the child
    exits. PYTHONUNBUFFERED keeps the child (pytest) from block-buffering its
    own stdout in the pipe -- without it, the log stays empty until ~8KB
    accumulates and the progress callback fires in bursts.

    `inhibit` wraps the command in `systemd-inhibit` (Linux) so a host
    suspend can't interrupt a long phase.
    """
    log_path.parent.mkdir(parents=True, exist_ok=True)
    if inhibit and sys.platform.startswith("linux"):
        cmd = _inhibit_cmd(cmd)
    full_env = os.environ.copy()
    full_env.setdefault("PYTHONUNBUFFERED", "1")
    if env:
        full_env.update(env)
    proc = subprocess.Popen(
        cmd, cwd=cwd, env=full_env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    with log_path.open("w", buffering=1) as log:
        for line in proc.stdout:
            log.write(line)
            if verbose:
                console.print(line, end="", highlight=False)
            if line_cb:
                line_cb(line)
    return proc.wait()


def run_parallel(cmds: list[tuple[str, list[str], dict[str, str] | None]], cwd: Path,
                 verbose: bool, line_cb=None, inhibit: bool = False) -> int:
    """Run several commands concurrently, each teeing to its own log file.

    `cmds` is a list of (name, cmd, extra_env). Logs go to LOG_DIR/<name>.log.
    Returns the worst (max) exit code. Logs are flushed per line and children
    run with PYTHONUNBUFFERED, same rationale as `run`. `inhibit` wraps each
    worker in `systemd-inhibit` (Linux).

    All workers are drained CONCURRENTLY (one reader thread each). Draining
    them one after another would leave the second worker's log empty and its
    progress callbacks unfired until the first worker exits, and would stall
    that worker once its stdout exceeded the OS pipe buffer (~64KB).
    """
    if inhibit and sys.platform.startswith("linux"):
        cmds = [(name, _inhibit_cmd(cmd), extra_env) for name, cmd, extra_env in cmds]
    procs = []
    for name, cmd, extra_env in cmds:
        log_path = LOG_DIR / f"{name}.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        full_env = os.environ.copy()
        full_env.setdefault("PYTHONUNBUFFERED", "1")
        if extra_env:
            full_env.update(extra_env)
        procs.append((name, subprocess.Popen(
            cmd, cwd=cwd, env=full_env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )))

    # Active workers share progress state, so serialize the callback.
    cb_lock = threading.Lock()
    rc_by_name: dict[str, int] = {}

    def _drain(name: str, proc: subprocess.Popen) -> None:
        log_path = LOG_DIR / f"{name}.log"
        with log_path.open("w", buffering=1) as log:
            for line in proc.stdout:
                log.write(line)
                if verbose:
                    console.print(line, end="", highlight=False)
                if line_cb:
                    with cb_lock:
                        line_cb(line)
        rc_by_name[name] = proc.wait()

    threads = [
        threading.Thread(target=_drain, args=(name, proc), name=f"drain-{name}")
        for name, proc in procs
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return max([0, *rc_by_name.values()])


def gradle(args: list[str], log_path: Path, verbose: bool, env: dict | None = None) -> int:
    return run([str(GRADLEW), *args], ROOT, log_path, verbose, env=env)


def pytest(args: list[str], log_path: Path, verbose: bool, env: dict | None = None,
           line_cb=None, inhibit: bool = False) -> int:
    cmd = [str(VENV_PYTHON), "-m", "pytest", *args]
    if inhibit and not sys.platform.startswith("linux"):
        console.print("[yellow]WARN[/] systemd-inhibit unavailable, skipping")
        inhibit = False
    return run(cmd, SYNC_TESTS, log_path, verbose, env=env, line_cb=line_cb,
               inhibit=inhibit)


# ---------------------------------------------------------------------------
# XML result parsing
# ---------------------------------------------------------------------------

def parse_junit_xml(path: Path) -> list[tuple[str, str]]:
    """Return [(classname, 'pass'|'fail')] from a JUnit XML file."""
    try:
        tree = ElementTree.parse(path)
    except (ElementTree.ParseError, FileNotFoundError):
        return []
    out = []
    for suite in tree.getroot().iter("testsuite"):
        name = suite.get("name") or ""
        failed = int(suite.get("failures", 0) or 0) + int(suite.get("errors", 0) or 0)
        out.append((name, "fail" if failed > 0 else "pass"))
    return out


def read_xml_results(xml_dir: Path) -> dict[str, str]:
    """Aggregate class -> status across every JUnit XML in a directory."""
    results: dict[str, str] = {}
    if not xml_dir.exists():
        return results
    for xml_file in sorted(xml_dir.glob("TEST-*.xml")):
        for name, status in parse_junit_xml(xml_file):
            results[name] = status
    return results


# ---------------------------------------------------------------------------
# Tier runners
# ---------------------------------------------------------------------------

def run_unit_tier(args, log_dir: Path, verbose: bool, progress: Progress,
                  db: dict) -> tuple[str, dict[str, str]]:
    task = progress.add_task("unit", total=1, tier="unit")
    if args.name or args.failed:
        classes = failed_classes_from_db(db, "unit") if args.failed \
            else [c for c in scan_unit_classes() if args.name in c]
        if not classes:
            console.print("[yellow]no matching unit test classes[/]")
            return "pass", {}
        gradle_args = ["testDebugUnitTest", *[f"--tests={c}" for c in classes]]
    else:
        gradle_args = ["testDebugUnitTest"]
    if args.passthrough:
        gradle_args += args.passthrough.split()
    start = time.time()
    rc = gradle(gradle_args, log_dir / "unit.log", verbose)
    duration = time.time() - start
    results = read_xml_results(UNIT_XML_DIR)
    failed = [c for c, s in results.items() if s == "fail"]
    status = "fail" if rc != 0 or failed else "pass"
    mark = "✗" if status == "fail" else "✓"
    npass = len(results) - len(failed)
    progress.update(task, completed=1, refresh=True,
                    description=f"{mark} unit ([green]{npass}✓[/] [red]{len(failed)}✗[/])",
                    failed=len(failed))
    record_tier(db, "unit", status, duration, results)
    return status, results


def run_instrumented_tier(args, log_dir: Path, verbose: bool, progress: Progress,
                          db: dict) -> tuple[str, dict[str, str]]:
    task = progress.add_task("instrumented", total=1, tier="instrumented")
    ensure_emulator("single", verbose)
    if args.name or args.failed:
        classes = failed_classes_from_db(db, "instrumented") if args.failed \
            else [c for c in scan_instrumented_classes() if args.name in c]
        # never run the host-driven probe through the tier sweep
        classes = [c for c in classes if c not in EXCLUDED_INSTRUMENTED_CLASSES]
        if not classes:
            console.print("[yellow]no matching instrumented test classes[/]")
            return "pass", {}
        runner_args = ",".join(classes)
        gradle_args = ["connectedDebugAndroidTest",
                       f"-Pandroid.testInstrumentationRunnerArguments.class={runner_args}"]
    else:
        not_class = ",".join(EXCLUDED_INSTRUMENTED_CLASSES)
        gradle_args = ["connectedDebugAndroidTest",
                       f"-Pandroid.testInstrumentationRunnerArguments.notClass={not_class}"]
    if args.passthrough:
        gradle_args += args.passthrough.split()
    start = time.time()
    rc = gradle(gradle_args, log_dir / "instrumented.log", verbose,
                env={"ANDROID_SERIAL": EMULATOR_A})
    duration = time.time() - start
    results = read_xml_results(INSTRUMENTED_XML_DIR)
    failed = [c for c, s in results.items() if s == "fail"]
    status = "fail" if rc != 0 or failed else "pass"
    mark = "✗" if status == "fail" else "✓"
    npass = len(results) - len(failed)
    progress.update(task, completed=1, refresh=True,
                    description=f"{mark} instrumented ([green]{npass}✓[/] [red]{len(failed)}✗[/])",
                    failed=len(failed))
    record_tier(db, "instrumented", status, duration, results)
    return status, results


def run_pytest_tier(tier: str, args, log_dir: Path, verbose: bool, progress: Progress,
                    db: dict) -> tuple[str, dict[str, str]]:
    spec = TIERS[tier]
    if spec["emulator"]:
        ensure_emulator(spec["emulator"], verbose)
    if spec["build_apk"]:
        gradle(["assembleDebug"], log_dir / f"{tier}-build.log", verbose)

    common = ["-v", "--tb=short", "--durations=5"]
    if args.name:
        common += ["-k", args.name]
    if args.passthrough:
        common += args.passthrough.split()

    if args.failed:
        # rerun exactly the tests recorded as failed in the DB last run (by
        # node id) rather than pytest's own --lf cache which can drift. Only
        # the node ids are passed -- NOT spec["pytest_args"], or pytest would
        # collect and run the whole tier alongside them. Fall back to the
        # full tier when no per-item detail is recorded yet.
        failed = [n for n, s in (db["tiers"].get(tier, {}).get("items") or {}).items()
                  if s == "fail"]
        if not failed:
            console.print(f"[yellow]{tier}: no per-test failure detail recorded; rerunning tier[/]")
            pytest_args = [*spec["pytest_args"], *common]
            total = collect_test_count(spec["pytest_args"], args.name)
        else:
            pytest_args = [*failed, *common]
            total = len(failed)
    else:
        pytest_args = [*spec["pytest_args"], *common]
        total = collect_test_count(spec["pytest_args"], args.name)

    task = progress.add_task(tier, total=total or None, tier=tier)
    seen = {"pass": 0, "fail": 0}
    state = {"done": 0}
    failed_items: list[str] = []

    def on_line(line: str):
        # pytest -v lines look like: "scenarios/...py::TestX::test_y PASSED [ 2%]"
        m = re.search(r"(\S+::\S+::\S+)\s+\b(PASSED|FAILED|ERROR|SKIPPED|XFAIL|XPASS)\b", line)
        if m:
            nodeid, result = m.group(1), m.group(2)
            key = "pass" if result in ("PASSED", "SKIPPED", "XFAIL", "XPASS") else "fail"
            seen[key] += 1
            state["done"] += 1
            if key == "fail":
                failed_items.append(nodeid)
            progress.update(task, completed=state["done"],
                            description=f"{tier} ([green]{seen['pass']}✓[/] [red]{seen['fail']}✗[/])",
                            failed=seen["fail"])

    start = time.time()
    rc = pytest(pytest_args, log_dir / f"{tier}.log", verbose,
                line_cb=on_line, inhibit=spec.get("inhibit_sleep", False))
    duration = time.time() - start
    status = "fail" if rc != 0 else "pass"
    mark = "✗" if status == "fail" else "✓"
    progress.update(task, completed=state["done"], total=state["done"], refresh=True,
                    description=f"{mark} {tier} ([green]{seen['pass']}✓[/] [red]{seen['fail']}✗[/])",
                    failed=seen["fail"])
    failed_map = {nid: "fail" for nid in failed_items}
    record_tier(db, tier, status, duration, failed_map)
    return status, failed_map


def run_scenario_tier(args, log_dir: Path, verbose: bool, progress: Progress,
                      db: dict) -> tuple[str, dict[str, str]]:
    ensure_emulator("both", verbose)
    gradle(["assembleDebug"], log_dir / "scenario-build.log", verbose)

    # --failed: rerun only the scenario tests recorded as failed last run.
    if args.failed:
        failed = [n for n, s in (db["tiers"].get("scenario", {}).get("items") or {}).items()
                  if s == "fail"]
        if not failed:
            console.print("[yellow]scenario: no per-test failure detail recorded; rerunning tier[/]")
            return _run_scenario_all(args, log_dir, verbose, progress, db)
        # Route the failed node ids through the same worker split.
        return _run_scenario_nodeids(failed, args, log_dir, verbose, progress, db)

    return _run_scenario_all(args, log_dir, verbose, progress, db)


def _run_scenario_all(args, log_dir, verbose, progress, db):
    total = EXPECTED_COUNTS["scenario"]
    task = progress.add_task("scenario", total=total, tier="scenario")
    seen = {"pass": 0, "fail": 0}
    state = {"done": 0, "rc": 0}
    failed_items: list[str] = []

    def on_line(line: str):
        # pytest -v lines look like: "scenarios/...py::TestX::test_y PASSED [ 2%]"
        m = re.search(r"(\S+::\S+::\S+)\s+\b(PASSED|FAILED|ERROR|SKIPPED|XFAIL|XPASS)\b", line)
        if m:
            nodeid, result = m.group(1), m.group(2)
            key = "pass" if result in ("PASSED", "SKIPPED", "XFAIL", "XPASS") else "fail"
            seen[key] += 1
            state["done"] += 1
            if key == "fail":
                failed_items.append(nodeid)
            progress.update(task, completed=state["done"],
                            description=f"scenario ([green]{seen['pass']}✓[/] [red]{seen['fail']}✗[/])",
                            failed=seen["fail"])

    # Phase 1: single-device workers run CONCURRENTLY (one per emulator slot)
    worker_cmds = []
    for worker, (serial, tests) in SCENARIO_WORKERS.items():
        worker_args = [str(VENV_PYTHON), "-m", "pytest", *tests, "-v"]
        if args.passthrough:
            worker_args += args.passthrough.split()
        worker_cmds.append((f"scenario-{worker}", worker_args,
                            {"EMULATOR_SERIAL": serial, "NEXTCLOUD_SUBDIR": worker}))
    start = time.time()
    inhibit = TIERS["scenario"].get("inhibit_sleep", False)
    rc = run_parallel(worker_cmds, SYNC_TESTS, verbose, line_cb=on_line, inhibit=inhibit)
    state["rc"] = max(state["rc"], rc)

    # Phase 2: serial two-device scenarios
    serial_args = [str(VENV_PYTHON), "-m", "pytest", *SCENARIO_SERIAL_ARGS, "-v"]
    if args.passthrough:
        serial_args += args.passthrough.split()
    rc = run(serial_args, SYNC_TESTS, log_dir / "scenario-serial.log",
             verbose, line_cb=on_line, inhibit=inhibit)
    state["rc"] = max(state["rc"], rc)

    duration = time.time() - start
    status = "fail" if state["rc"] != 0 else "pass"
    mark = "✗" if status == "fail" else "✓"
    progress.update(task, completed=state["done"], total=state["done"], refresh=True,
                    description=f"{mark} scenario ([green]{seen['pass']}✓[/] [red]{seen['fail']}✗[/])",
                    failed=seen["fail"])
    failed_map = {nid: "fail" for nid in failed_items}
    record_tier(db, "scenario", status, duration, failed_map)
    return status, failed_map


def _run_scenario_nodeids(nodeids: list[str], args, log_dir, verbose, progress, db):
    """--failed path: run only the given scenario node ids (serial, single pass)."""
    task = progress.add_task("scenario", total=len(nodeids), tier="scenario")
    seen = {"pass": 0, "fail": 0}
    state = {"done": 0, "rc": 0}
    failed_items: list[str] = []

    def on_line(line: str):
        m = re.search(r"(\S+::\S+::\S+)\s+\b(PASSED|FAILED|ERROR|SKIPPED|XFAIL|XPASS)\b", line)
        if m:
            nodeid, result = m.group(1), m.group(2)
            key = "pass" if result in ("PASSED", "SKIPPED", "XFAIL", "XPASS") else "fail"
            seen[key] += 1
            state["done"] += 1
            if key == "fail":
                failed_items.append(nodeid)
            progress.update(task, completed=state["done"],
                            description=f"scenario ([green]{seen['pass']}✓[/] [red]{seen['fail']}✗[/])",
                            failed=seen["fail"])

    # Failed two-device tests still need both emulators in one session; the
    # single-device ones can run in parallel, but for simplicity and because
    # --failed is usually a small set, run them all serially here.
    cmd = [str(VENV_PYTHON), "-m", "pytest", *nodeids, "-v"]
    if args.passthrough:
        cmd += args.passthrough.split()
    start = time.time()
    rc = run(cmd, SYNC_TESTS, log_dir / "scenario-failed.log", verbose,
             line_cb=on_line, inhibit=TIERS["scenario"].get("inhibit_sleep", False))
    state["rc"] = rc
    duration = time.time() - start
    status = "fail" if state["rc"] != 0 else "pass"
    mark = "✗" if status == "fail" else "✓"
    progress.update(task, completed=state["done"], total=state["done"], refresh=True,
                    description=f"{mark} scenario ([green]{seen['pass']}✓[/] [red]{seen['fail']}✗[/])",
                    failed=seen["fail"])
    failed_map = {nid: "fail" for nid in failed_items}
    record_tier(db, "scenario", status, duration, failed_map)
    return status, failed_map


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def ensure_emulator(mode: str, verbose: bool) -> None:
    """Boot emulator(s) from the sync_test_ready snapshot.

    mode: 'single' -> testPixel7 (emulator-5554); 'both' -> A + B.
    """
    env = {}
    if mode == "single":
        env["EMULATOR_SERIAL"] = EMULATOR_A
    console.print(f"[cyan]booting emulator ({mode})...[/]")
    run([str(VENV_PYTHON), str(EMULATOR_SCRIPT)], SYNC_TESTS, LOG_DIR / "emulator.log",
        verbose, env=env)


def scan_unit_classes() -> list[str]:
    """Best-effort scan of app/src/test for test class names."""
    base = ROOT / "app" / "src" / "test" / "java"
    classes = []
    for f in sorted(base.rglob("*Test.kt")):
        rel = f.relative_to(base)
        pkg = ".".join(rel.parts[:-1])
        classes.append(f"{pkg}.{f.stem}")
    return classes


def scan_instrumented_classes() -> list[str]:
    base = ROOT / "app" / "src" / "androidTest" / "java"
    classes = []
    for f in sorted(base.rglob("*Test.kt")):
        rel = f.relative_to(base)
        pkg = ".".join(rel.parts[:-1])
        name = f"{pkg}.{f.stem}"
        if name in EXCLUDED_INSTRUMENTED_CLASSES:
            continue
        classes.append(name)
    return classes


def failed_classes_from_db(db: dict, tier: str) -> list[str]:
    items = db["tiers"].get(tier, {}).get("items") or {}
    return [name for name, status in items.items() if status == "fail"]


def collect_test_count(paths: list[str], name_filter: str | None) -> int | None:
    """Count collected pytest tests (best-effort; None when it fails)."""
    args = ["--collect-only", "-q", *paths]
    if name_filter:
        args += ["-k", name_filter]
    try:
        proc = subprocess.run(
            [str(VENV_PYTHON), "-m", "pytest", *args],
            cwd=SYNC_TESTS, capture_output=True, text=True, timeout=60,
        )
    except subprocess.TimeoutExpired:
        return None
    m = re.search(r"(\d+) tests collected", proc.stdout)
    return int(m.group(1)) if m else None


# ---------------------------------------------------------------------------
# Gating + orchestration
# ---------------------------------------------------------------------------

def gate_check(tier: str, db: dict, args) -> str | None:
    """Return a skip reason if the tier must not run, else None."""
    deps = TIERS[tier]["deps"]
    if not deps:
        return None
    if args.all_fresh:
        # same-run gating: outcomes of deps executed in this invocation
        for dep in deps:
            if db["tiers"].get(dep, {}).get("status") == "fail":
                return f"dep '{dep}' failed in this run"
    else:
        # recorded-result gating (--group): consult the DB
        for dep in deps:
            status = db["tiers"].get(dep, {}).get("status")
            if status == "fail" and not args.force:
                return f"dep '{dep}' failed (recorded)"
            if status is None:
                console.print(f"[yellow]WARN[/] no recorded result for dep '{dep}', proceeding")
    return None


def build_plan(args, db: dict) -> list[str]:
    """Tiers to execute, respecting gating and selection."""
    if args.group:
        groups = [g.strip() for g in args.group.split(",") if g.strip()]
        unknown = [g for g in groups if g not in TIERS]
        if unknown:
            raise SystemExit(f"unknown group(s): {', '.join(unknown)}; "
                             f"available: {', '.join(ORDER)}")
        return [g for g in ORDER if g in groups]
    if args.failed:
        # only tiers that recorded failed items in the last run
        return [t for t in ORDER if tier_has_failures(db, t)]
    return list(ORDER)


def tier_has_failures(db: dict, tier: str) -> bool:
    """True if the tier's last recorded run has any failed items.

    Falls back to the recorded tier status when no per-item detail exists
    (e.g. DB written before item tracking was added) so `--failed` still
    reruns a tier that was recorded as failed.
    """
    entry = db["tiers"].get(tier, {})
    items = entry.get("items") or {}
    if any(v == "fail" for v in items.values()):
        return True
    return entry.get("status") == "fail"


def format_duration(seconds: float | None) -> str:
    """Format seconds as HH:MM:SS; '--:--:--' when unknown."""
    if seconds is None:
        return "--:--:--"
    seconds = max(0, int(seconds))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h:02d}:{m:02d}:{s:02d}"


def suite_eta_seconds(db: dict, remaining: list[str]) -> float | None:
    """Estimated total seconds for the remaining tiers, from recorded run
    history (average of the last few durations). 0 when nothing remains;
    None when no history exists."""
    if not remaining:
        return 0.0
    total = 0.0
    known = 0
    for tier in remaining:
        d = avg_duration(db, tier)
        if d:
            total += d
            known += 1
    return total if known else None


def run_plan(args) -> int:
    db = load_db()
    plan = build_plan(args, db)
    all_failed = 0
    skipped: list[str] = []

    if args.failed and not plan:
        console.print("[yellow]No failed tests recorded; nothing to rerun.[/]")
        return 0

    if args.dry_run:
        table = Table(title="Test plan")
        table.add_column("tier")
        table.add_column("deps")
        table.add_column("est. duration")
        table.add_column("description")
        for tier in plan:
            d = avg_duration(db, tier)
            table.add_row(tier, ",".join(TIERS[tier]["deps"]) or "-",
                          f"{d:.0f}s" if d else "-", TIERS[tier]["description"])
        console.print(table)
        console.print(f"overall ETA: {format_duration(suite_eta_seconds(db, plan))}")
        return 0

    snapshots: dict[str, float | None] = {}
    remaining = list(plan)
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        OutcomeBarColumn(),
        TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
        TimeElapsedColumn(),
        TaskETA(db, remaining, snapshots),
        console=console,
        expand=True,
    ) as progress:
        for tier in plan:
            reason = gate_check(tier, db, args)
            if reason:
                console.print(f"[yellow]SKIP {tier}[/]: {reason}")
                skipped.append(tier)
                remaining.remove(tier)
                continue
            console.print(f"[bold cyan]==> {tier}[/] ({TIERS[tier]['description']})")
            start = time.time()
            if TIERS[tier]["runner"] == "gradle_unit":
                status, _ = run_unit_tier(args, LOG_DIR, args.verbose, progress, db)
            elif TIERS[tier]["runner"] == "gradle_instrumented":
                status, _ = run_instrumented_tier(args, LOG_DIR, args.verbose, progress, db)
            elif TIERS[tier]["runner"] == "pytest":
                status, _ = run_pytest_tier(tier, args, LOG_DIR, args.verbose, progress, db)
            elif TIERS[tier]["runner"] == "scenario":
                status, _ = run_scenario_tier(args, LOG_DIR, args.verbose, progress, db)
            else:
                raise SystemExit(f"unknown runner for {tier}")
            remaining.remove(tier)
            snapshots[tier] = suite_eta_seconds(db, remaining)
            if status == "fail":
                all_failed += 1
            save_db(db)

    # failed-test detail (the tier table is redundant with the progress bars)
    failed_any = False
    for tier in plan:
        if tier in skipped:
            continue
        entry = db["tiers"].get(tier, {})
        failed = [n for n, s in (entry.get("items") or {}).items() if s == "fail"]
        if failed:
            failed_any = True
            console.print(f"[red]Failed ({tier}):[/]")
            for n in failed:
                console.print(f"  [red]✗[/] {n}")
    if not failed_any:
        console.print("[green]All executed tests passed.[/]")
    return 1 if all_failed else 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="PicPocket unified test runner")
    parser.add_argument("--all", dest="all_fresh", action="store_true", default=True,
                        help="run every tier in dependency order, gating on same-run "
                             "outcomes (default)")
    parser.add_argument("--group", metavar="X[,Y...]",
                        help="run only the given tier group(s); dependencies are "
                             "consulted in the results DB (warn-and-proceed when absent)")
    parser.add_argument("--name", metavar="PATTERN",
                        help="run only tests whose name contains PATTERN")
    parser.add_argument("--failed", action="store_true",
                        help="rerun only tests that failed in the last run")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="stream all subprocess output to the console")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the plan and estimated ETA, run nothing")
    parser.add_argument("--force", action="store_true",
                        help="override recorded-dependency skips (with --group)")
    parser.add_argument("--args", dest="passthrough", metavar="RAW",
                        help="extra arguments appended to the underlying runner "
                             "(gradle flags for gradle tiers, pytest flags for "
                             "pytest tiers); use quotes, e.g. --args \"-k expr\"")
    args = parser.parse_args()

    # --all is the default; any other selector turns it off (recorded gating)
    if args.group or args.name or args.failed or args.force:
        args.all_fresh = False
    if not (args.all_fresh or args.group or args.name or args.failed):
        args.all_fresh = True  # bare invocation => full run

    try:
        rc = run_plan(args)
    except KeyboardInterrupt:
        console.print("\n[yellow]interrupted[/]")
        rc = 130
    sys.exit(rc)


if __name__ == "__main__":
    main()