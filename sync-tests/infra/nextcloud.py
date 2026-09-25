"""Nextcloud test infrastructure management."""

import fcntl
import json
import logging
import os
import shutil
import subprocess
import time
from pathlib import Path

logger = logging.getLogger(__name__)

COMPOSE_FILE = Path(__file__).parent.parent / "docker-compose.test.yml"
NEXTCLOUD_CONTAINER = "sync-tests-nextcloud-1"
NEXTCLOUD_USER = "testuser"
PASS_PREFIX = "app/picpocket/tester"
NCDATA_MOUNT_POINT = Path(__file__).parent.parent.parent / "tmp" / "nc_data"

OCC = ["php", "/var/www/html/occ"]


def _run(cmd: list[str], check: bool = True, **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, check=check, **kwargs)


def _fail(msg: str, result: subprocess.CompletedProcess) -> str:
    """Format a failure message from a command result (stdout or stderr)."""
    detail = (result.stderr or result.stdout or "").strip()
    return f"{msg}: {detail}" if detail else msg


def _pass_get(key: str) -> str:
    result = _run(["pass", f"{PASS_PREFIX}/{key}"])
    return result.stdout.strip()


def _wait_for_healthy(timeout: int = 300) -> None:
    start = time.time()
    while time.time() < start + timeout:
        try:
            result = _run(
                ["docker", "exec", NEXTCLOUD_CONTAINER] + OCC + ["status"],
                check=False,
            )
            if "installed: true" in result.stdout:
                logger.info("Nextcloud is healthy")
                return
            logger.debug("Nextcloud not installed yet: %s", result.stdout.strip())
        except subprocess.CalledProcessError as e:
            logger.debug("Health check failed: %s", e)
        time.sleep(5)
    raise TimeoutError(f"Nextcloud not healthy after {timeout}s")


def _occ(args: list[str], check: bool = False) -> subprocess.CompletedProcess:
    return _run(
        ["docker", "exec", NEXTCLOUD_CONTAINER] + OCC + args,
        check=check,
    )


def scan_path(user_path: str) -> None:
    """Rescan one Nextcloud path so its filecache picks up external-storage
    changes. `user_path` is relative to the user's files, e.g.
    "PicPocketTest/<docId>". Used as a last-resort filecache refresh when a
    just-synced file isn't listed yet (the external storage is rclone-backed
    and lags). Best-effort: logs and never raises.
    """
    rel = user_path.strip("/")
    result = _occ(["files:scan", f"--path=/{NEXTCLOUD_USER}/files/{rel}"])
    if result.returncode != 0:
        logger.warning(_fail(f"files:scan --path={rel} failed", result))
    else:
        logger.info("Nextcloud filecache rescan requested for %s", rel)



def _get_picpockettest_mounts() -> list[dict]:
    result = _occ(["files_external:list", "--output=json"])
    if result.returncode != 0:
        logger.error(_fail("Failed to list external mounts", result))
        return []
    try:
        mounts = json.loads(result.stdout)
        return [m for m in mounts if m.get("mount_point") == "/PicPocketTest"]
    except json.JSONDecodeError as e:
        logger.error("Failed to parse mount list: %s", e)
        return []


def _configure_local_mount() -> None:
    existing = _get_picpockettest_mounts()
    if existing:
        mount_id = existing[0]["mount_id"]
        logger.info(
            "Mount %d for PicPocketTest already exists (total %d duplicates)",
            mount_id, len(existing),
        )
    else:
        result = _occ([
            "files_external:create",
            "PicPocketTest",
            "local",
            "null::null",
            "-c", "datadir=/mnt/gdrive",
        ])
        if result.returncode != 0:
            logger.error(_fail("Failed to create PicPocketTest mount", result))
            return
        logger.info("Created PicPocketTest mount")
        mounts = _get_picpockettest_mounts()
        if not mounts:
            logger.error("PicPocketTest mount not found after creation")
            return
        mount_id = mounts[0]["mount_id"]

    result = _occ(["files_external:applicable", str(mount_id), "--add-user", "testuser"])
    if result.returncode != 0:
        logger.error(_fail(f"Failed to assign mount {mount_id} to testuser", result))
        return

    # Verify assignment
    mounts = _get_picpockettest_mounts()
    updated = [m for m in mounts if "testuser" in m.get("applicable_users", [])]
    if updated:
        logger.info("Mount %d assigned to testuser: verified", mount_id)
    else:
        logger.warning(
            "Mount %d assigned but applicable_users not reflected: %s",
            mount_id, mounts,
        )


RCLONE_MOUNT_POINT = Path(__file__).parent.parent.parent / "tmp" / "gdrive-test"
RCLONE_REMOTE = "gtest"
RCLONE_REMOTE_PATH = "PicPocketTest"
RCLONE_DIR_CACHE_TIME = 10
DOCKERFILE_PATH = Path(__file__).parent.parent / "Dockerfile.nextcloud-rclone"
BUILD_CONTEXT = Path(__file__).parent.parent
IMAGE_TAG = "nextcloud-rclone:test"

# Worker isolation: each parallel pytest session operates on its OWN subfolder
# of the shared Drive root (NEXTCLOUD_SUBDIR=w1 -> gtest:PicPocketTest/w1).
# Empty/absent = the default worker rooted at PicPocketTest itself (legacy
# single-session behavior). One rclone mount + one Nextcloud external-storage
# mount stays shared; only the worker's own folder is read/written/purged, so
# devices.json + sync-lock.json are per-folder and cannot collide.
DRIVE_SUBDIR = os.environ.get("NEXTCLOUD_SUBDIR", "").strip()


def _worker_remote_path() -> str:
    """rclone remote path of this worker's root folder."""
    base = f"{RCLONE_REMOTE}:{RCLONE_REMOTE_PATH}"
    return f"{base}/{DRIVE_SUBDIR}" if DRIVE_SUBDIR else base


def _worker_mount_dir() -> Path:
    """Host mount path of this worker's root folder (kept in existence so the
    SAF picker can select it; only its CONTENTS are purged)."""
    return RCLONE_MOUNT_POINT / DRIVE_SUBDIR if DRIVE_SUBDIR else RCLONE_MOUNT_POINT


def _is_rclone_mounted() -> bool:
    """Check if rclone mount is already active at RCLONE_MOUNT_POINT."""
    try:
        if not RCLONE_MOUNT_POINT.exists():
            return False
    except OSError as e:
        # A dead FUSE mount makes stat() raise "Transport endpoint is not
        # connected" instead of returning False. Treat it as unmounted and
        # let the caller clear the stale transport endpoint.
        logger.warning("Stale rclone mount at %s (%s) — treating as unmounted", RCLONE_MOUNT_POINT, e)
        return False
    result = _run(["mountpoint", "-q", str(RCLONE_MOUNT_POINT)], check=False)
    return result.returncode == 0


def _container_mount_ok() -> bool:
    """Check the running Nextcloud container can actually read /mnt/gdrive."""
    result = _run(
        ["docker", "exec", NEXTCLOUD_CONTAINER, "ls", "/mnt/gdrive/"],
        check=False,
    )
    if result.returncode == 0:
        return True
    logger.warning(
        "Nextcloud container cannot read /mnt/gdrive (%s) — restarting it",
        (result.stderr or result.stdout or "").strip(),
    )
    _run(["docker", "restart", NEXTCLOUD_CONTAINER], check=False)


def _verify_rclone_io() -> bool:
    """Write a test file to the mount and read it back to verify I/O works."""
    test_file = RCLONE_MOUNT_POINT / ".rclone_verify"
    try:
        test_file.write_text("verify")
        content = test_file.read_text()
        test_file.unlink()
        if content == "verify":
            return True
        logger.error("rclone readback mismatch: got %r", content)
        return False
    except OSError as e:
        # Clean up if file was created but read failed
        if test_file.exists():
            test_file.unlink(missing_ok=True)
        logger.error("rclone I/O error: %s", e)
        return False


_MOUNT_LOCK = Path(__file__).parent.parent.parent / "tmp" / ".rclone_mount.lock"
_STACK_LOCK = Path(__file__).parent.parent.parent / "tmp" / ".nextcloud_stack.lock"


def _ensure_rclone_mount() -> None:
    """Mount rclone to the test mount point if not already mounted.

    Serialized with a file lock: the mount is a single shared resource across
    parallel worker sessions. Without the lock, two workers starting together
    could each see the mount as briefly down and force-unmount/re-mount it
    concurrently, tearing it down under the other's in-flight readback.
    """
    _MOUNT_LOCK.parent.mkdir(parents=True, exist_ok=True)
    with open(_MOUNT_LOCK, "w") as lock_fh:
        fcntl.flock(lock_fh, fcntl.LOCK_EX)
        try:
            _ensure_rclone_mount_locked()
        finally:
            fcntl.flock(lock_fh, fcntl.LOCK_UN)


def _ensure_rclone_mount_locked() -> None:
    """Actual mount/verify logic; runs under the rclone mount lock."""
    mounted = _is_rclone_mounted()
    if mounted:
        logger.info("rclone process running at %s", RCLONE_MOUNT_POINT)
    else:
        # A dead rclone daemon leaves a stale FUSE transport endpoint behind;
        # fusermount clears it so the fresh mount lands on a clean directory.
        # Harmless if the path is not actually mounted.
        _run(["fusermount", "-u", "-z", str(RCLONE_MOUNT_POINT)], check=False)
        time.sleep(1)
        RCLONE_MOUNT_POINT.mkdir(parents=True, exist_ok=True)
        VFS_CACHE_DIR = RCLONE_MOUNT_POINT.parent / "rclone-vfs-cache"
        VFS_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        # rclone mount fails if the remote root does not exist (e.g. after a
        # sync-clean purge). Create it idempotently first — mkdir is a no-op
        # when the path is already there. Safe: no mount is running at this
        # point, so no dir-cache can desync (see purge_drive's warning).
        _run(
            ["rclone", "mkdir", "--recursive", f"{RCLONE_REMOTE}:{RCLONE_REMOTE_PATH}"],
            check=False,
        )
        result = _run(
            [
                "rclone", "mount",
                f"{RCLONE_REMOTE}:{RCLONE_REMOTE_PATH}",
                str(RCLONE_MOUNT_POINT),
                "--daemon",
                "--allow-other",
                "--uid=33", "--gid=33",
                "--vfs-cache-mode", "writes",
                "--drive-use-trash=false",
                "--cache-dir", str(VFS_CACHE_DIR),
                "--dir-cache-time", f"{RCLONE_DIR_CACHE_TIME}s",
                "--poll-interval", "0",
                # Worker-root folders (NEXTCLOUD_SUBDIR=w1/w2) are mirrored
                # through the FUSE mount; after a hard kill or crash the plain
                # directories remain in the mount point and rclone refuses to
                # mount over a non-empty dir ("is not empty, use
                # --allow-non-empty"), failing the daemon child with a
                # misleading "Daemon timed out" from the parent. Mounting over
                # them is safe: the FUSE fs shadows the stale entries.
                "--allow-non-empty",
            ],
            check=False,
        )
        if result.returncode != 0:
            if _is_rclone_mounted():
                logger.info("rclone already mounted at %s", RCLONE_MOUNT_POINT)
            else:
                logger.error(_fail("rclone mount failed", result))
                raise RuntimeError(_fail("rclone mount failed", result))
        time.sleep(2)
        if not _is_rclone_mounted():
            logger.error("rclone mount not active after mount command")
            return
        logger.info("rclone mounted at %s", RCLONE_MOUNT_POINT)

    if _verify_rclone_io():
        logger.info("rclone I/O verified (write+read OK)")
    else:
        raise RuntimeError(
            f"rclone mount at {RCLONE_MOUNT_POINT} failed readback check — "
            "Google Drive may not be accessible"
        )


def drive_state() -> list[dict]:
    """Recursively list this worker's Drive folder via rclone lsjson.

    Reads Google Drive directly (independent of the FUSE mount), returning
    file rows with Size, MimeType and MD5 keys.
    """
    result = _run(
        ["rclone", "lsjson", "--recursive", "--hash", _worker_remote_path()],
        check=False,
    )
    if result.returncode != 0:
        logger.warning(
            "rclone lsjson failed (rc=%d): %s",
            result.returncode, (result.stderr or result.stdout or "").strip(),
        )
        return []
    try:
        rows = json.loads(result.stdout)
    except json.JSONDecodeError as e:
        logger.warning("rclone lsjson parse error: %s", e)
        return []
    return [r for r in rows if not r.get("IsDir")]


def _drive_listing() -> list[dict]:
    """Raw recursive lsjson of this worker's Drive folder, INCLUDING dirs.

    Used for emptiness checks where directories must not be ignored.
    Returns [] on any failure (like drive_state, but without the IsDir filter).
    """
    result = _run(
        ["rclone", "lsjson", "--recursive", _worker_remote_path()],
        check=False,
    )
    if result.returncode != 0:
        logger.warning(
            "rclone lsjson failed (rc=%d): %s",
            result.returncode, (result.stderr or result.stdout or "").strip(),
        )
        return []
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as e:
        logger.warning("rclone lsjson parse error: %s", e)
        return []


def _drive_listing_strict() -> list[dict]:
    """_drive_listing that raises on rclone failure instead of returning [].

    purge_drive must never "succeed" because a transient rclone error made the
    emptiness check look empty. Each `rclone lsjson` invocation lists Google
    Drive fresh (no cross-invocation dir cache), so this is the live backend.
    """
    result = _run(
        ["rclone", "lsjson", "--recursive", _worker_remote_path()],
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            "rclone lsjson failed while verifying Drive purge "
            f"(rc={result.returncode}): {(result.stderr or result.stdout or '').strip()}"
        )
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as e:
        raise RuntimeError(
            f"rclone lsjson parse error while verifying Drive purge: {e}"
        ) from e


def purge_drive(timeout: int = 120) -> None:
    """Definitively empty THIS worker's Drive folder and reconcile Nextcloud.

    Deletes through the FUSE mount (rmtree/unlink on the mount point) so the
    mount's VFS/dir cache and the remote folder ID stay in sync. An out-of-band
    `rclone purge` + `mkdir` would recreate the folder under a new Drive ID,
    desync the running mount, and silently break all subsequent uploads, so it
    must not be used. Includes empty directories. The worker's root folder
    itself is kept (created if missing) so the SAF picker can select it; only
    its contents are removed. Re-scans Nextcloud so no phantom filecache
    entries remain. Raises TimeoutError if Drive stays non-empty.
    """
    root = _worker_mount_dir()
    root.mkdir(parents=True, exist_ok=True)

    def _delete_children() -> None:
        if not root.exists():
            return
        for child in list(root.iterdir()):
            if child.is_dir() and not child.is_symlink():
                shutil.rmtree(child)
            else:
                child.unlink(missing_ok=True)

    _delete_children()
    deadline = time.time() + timeout
    try:
        while _drive_listing_strict() and time.time() < deadline:
            logger.warning("Drive not empty after delete, retrying")
            _delete_children()
            time.sleep(5)
    except RuntimeError as e:
        if "directory not found" in str(e):
            logger.info("Drive worker folder does not exist yet, nothing to purge")
        else:
            raise
    try:
        rows = _drive_listing_strict()
    except RuntimeError as e:
        if "directory not found" in str(e):
            rows = []
        else:
            raise
    if rows:
        names = [r.get("Path", r.get("Name", "?")) for r in rows[:10]]
        raise TimeoutError(f"Drive {DRIVE_SUBDIR or 'PicPocketTest'} not empty after purge: {names}")
    _occ(["files:scan", "--all"], check=False)
    logger.info("Drive %s purged; Nextcloud re-scanned", DRIVE_SUBDIR or "PicPocketTest")


def empty_drive_trash(timeout: int = 120) -> None:
    """Permanently delete trashed items under THIS worker's Drive folder.

    Test deletes accumulate in the Drive bin because rclone's default
    --drive-use-trash=true sends deletes to trash. rclone has no native
    empty-trash command, so list trashed items scoped to the worker folder
    (--drive-trashed-only) and permanently delete them
    (--drive-use-trash=false). Best-effort: logs failures, never raises,
    so a cleanup problem can't fail the test session.
    """
    worker_remote = _worker_remote_path()

    def _trashed_rows() -> list[dict]:
        result = _run(
            ["rclone", "lsjson", "--drive-trashed-only", "--recursive",
             worker_remote],
            check=False,
        )
        if result.returncode != 0:
            logger.warning(
                "rclone trashed-only lsjson failed (rc=%d): %s",
                result.returncode, (result.stderr or result.stdout or "").strip(),
            )
            return []
        try:
            rows = json.loads(result.stdout)
        except json.JSONDecodeError as e:
            logger.warning("rclone trashed-only lsjson parse error: %s", e)
            return []
        # Only files matter: `rclone delete` cannot remove directories, and a
        # trashed empty directory (left behind by doc-folder deletes) would
        # otherwise keep the retry loop below spinning until the timeout.
        # Empty trashed dirs are harmless and purged by Drive's bin retention.
        return [r for r in rows if not r.get("IsDir")]

    if not _trashed_rows():
        logger.info("Drive bin is already empty")
        return

    result = _run(
        ["rclone", "delete", "--drive-trashed-only", "--drive-use-trash=false",
         worker_remote],
        check=False,
    )
    if result.returncode != 0:
        logger.warning(
            "rclone drive bin cleanup failed (rc=%d): %s",
            result.returncode, (result.stderr or result.stdout or "").strip(),
        )
        return

    deadline = time.time() + timeout
    while _trashed_rows() and time.time() < deadline:
        logger.warning("Drive bin not empty after delete, retrying")
        time.sleep(5)
    remaining = _trashed_rows()
    if remaining:
        names = [r.get("Path", r.get("Name", "?")) for r in remaining[:10]]
        logger.warning("Drive bin not empty after cleanup: %s", names)
    else:
        logger.info("Drive bin purged (PicPocketTest trash emptied)")


def purge_remote_dir(rel_path: str) -> bool:
    """Permanently delete a directory under THIS worker's folder via backend.

    rclone purge hits the Drive API directly, bypassing the FUSE mount
    listing, so it also removes files that a FUSE-based rmtree missed because
    their async upload (vfs write-back) was still in flight when the cleanup
    ran. Returns True if the path was present on the backend and purged,
    False if it was already gone. Best-effort: never raises, so a cleanup
    problem can't fail a test.
    """
    target = f"{_worker_remote_path()}/{rel_path}"
    check = _run(["rclone", "lsjson", "--recursive", target], check=False)
    if check.returncode != 0:
        return False
    result = _run(["rclone", "purge", "--drive-use-trash=false", target], check=False)
    if result.returncode != 0:
        logger.warning(
            "rclone purge %s failed (rc=%d): %s",
            rel_path, result.returncode,
            (result.stderr or result.stdout or "").strip(),
        )
        return False
    logger.info("Drive backend purge complete: %s", rel_path)
    return True


def purge_folder_via_mount(rel_path: str, timeout: int = 120) -> bool:
    """Permanently delete a directory under THIS worker's folder via the mount.

    Same intent as purge_drive but scoped to a single subfolder, so it does not
    touch the rest of the worker's remote tree. Deletes through the mount
    (rmtree on the mount point) to keep the mount's VFS/dir-cache and the
    remote folder ID in sync, polls Drive until the folder is gone, and
    re-scans Nextcloud so no phantom filecache entries remain. An out-of-band
    `rclone purge` must not be used here: it desyncs the running mount's
    dir-cache, and a later mkdir of the same path silently breaks all
    subsequent uploads. Returns True if the folder existed and was purged,
    False otherwise. Never raises.
    """
    target = _worker_mount_dir() / rel_path
    existed = target.exists()
    if not existed:
        return False

    def _rmtree() -> None:
        if target.is_dir() and not target.is_symlink():
            shutil.rmtree(target)
        else:
            target.unlink(missing_ok=True)

    _rmtree()
    deadline = time.time() + timeout
    while _folder_on_drive(rel_path) and time.time() < deadline:
        logger.warning("Drive %s not empty after mount delete, retrying", rel_path)
        _rmtree()
        time.sleep(5)
    if _folder_on_drive(rel_path):
        logger.warning("Drive %s still present after purge", rel_path)
    else:
        logger.info("Drive %s purged via mount; Nextcloud re-scanned", rel_path)
    _occ(["files:scan", "--all"], check=False)
    return True


def _folder_on_drive(rel_path: str) -> bool:
    """True if rel_path (or anything under it) still exists on Drive."""
    rows = _drive_listing()
    prefix = rel_path.rstrip("/") + "/"
    return any(
        r.get("Path") == rel_path or r.get("Path", "").startswith(prefix)
        for r in rows
    )


def wait_drive_finalized(path: str, min_size: int = 0, timeout: float = 180.0) -> list[dict]:
    """Wait until the Drive copy of path is fully uploaded and finalized.

    A file is finalized when its MIME type is not the unfinalized-upload
    marker 'application/x-partial-download', its size is >= min_size, and it
    has a real MD5 (i.e. rclone could fully read it). Returns the final
    matching lsjson rows (possibly incomplete on timeout).
    """
    deadline = time.time() + timeout
    last: list[dict] = []
    while True:
        rows = drive_state()
        # Directories (lsjson IsDir rows) have no Size/Hashes and must not be
        # judged as files — the mutex's .sync-lock directory may legitimately
        # exist on Drive during or after a sync.
        files = [r for r in rows if not r.get("IsDir")]
        if path in ("", "/"):
            last = files
        else:
            prefix = path.rstrip("/") + "/"
            last = [r for r in files if r.get("Path") == path or r.get("Path", "").startswith(prefix)]
        if last and all(
            r.get("MimeType") != "application/x-partial-download"
            and r.get("Size", 0) >= min_size
            and (r.get("Hashes") or {}).get("md5")
            for r in last
        ):
            return last
        if time.time() >= deadline:
            logger.warning(
                "wait_drive_finalized %r timed out after %.0fs: %d file(s)",
                path, timeout, len(last),
            )
            return last
        time.sleep(2)


def _image_exists(tag: str = IMAGE_TAG) -> bool:
    """Check if a Docker image exists locally."""
    result = _run(["docker", "images", "-q", tag], check=False)
    return bool(result.stdout.strip())


def _ensure_docker_image() -> None:
    """Build the custom Nextcloud image if not already present."""
    if _image_exists():
        logger.info("Docker image %s already exists", IMAGE_TAG)
        return
    logger.info("Building Docker image %s...", IMAGE_TAG)
    _run(
        ["docker", "build", "--network", "host", "-t", IMAGE_TAG,
         str(BUILD_CONTEXT), "-f", str(DOCKERFILE_PATH)],
    )
    logger.info("Docker image %s built", IMAGE_TAG)


def _clean_nc_data() -> None:
    """Remove nc_data directory using Docker (files owned by www-data)."""
    if NCDATA_MOUNT_POINT.exists():
        parent = str(NCDATA_MOUNT_POINT.parent)
        name = NCDATA_MOUNT_POINT.name
        _run(
            ["docker", "run", "--rm", "-v", f"{parent}:/tmp/work", "alpine",
             "rm", "-rf", f"/tmp/work/{name}"],
            check=False,
        )
        if NCDATA_MOUNT_POINT.exists():
            shutil.rmtree(str(NCDATA_MOUNT_POINT), ignore_errors=True)
    NCDATA_MOUNT_POINT.mkdir(parents=True, exist_ok=True)
    logger.info("nc_data cleaned")


def _user_exists(username: str) -> bool:
    result = _occ(["user:list", "--output=json"], check=False)
    if result.returncode != 0:
        return False
    try:
        users = json.loads(result.stdout)
        return username in users
    except (json.JSONDecodeError, KeyError):
        return False


def _is_already_setup() -> bool:
    return _user_exists("testuser")


def _install_fresh() -> None:
    """Install a fresh Nextcloud instance via the web installer.

    `occ maintenance:install` is NOT available on an uninstalled instance
    (occ only loads a limited command set), and the official image's
    entrypoint auto-install can silently no-op ("Cannot write into config
    directory!", exit code 0), leaving the instance uninstalled forever. The
    canonical path is the web installer: the image ships config/autoconfig.php
    which reads the MYSQL_* and NEXTCLOUD_ADMIN_* env vars and completes the
    install on the first page request. Drive it with curl, then the caller's
    health wait confirms `occ status` reports installed.
    """
    for attempt in range(20):
        try:
            result = _run(
                ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                 "http://localhost:8080/"],
                check=False,
            )
            code = result.stdout.strip()
            if code and code != "000":
                logger.info("Fresh Nextcloud web install triggered (HTTP %s)", code)
                return
        except Exception:
            pass
        time.sleep(3)
    raise RuntimeError("Fresh Nextcloud web install did not respond in time")


def _start_stack(fresh: bool = False) -> None:
    """docker compose up + (fresh install) + health-wait, serialized across
    parallel workers.

    `docker compose up -d` is NOT concurrency-safe: two workers cold-starting
    the same project together each race the container creates and one exits
    non-zero. Mirror the rclone mount lock: hold a file lock across up +
    install + health wait so the loser's up -d runs after the winner's
    containers already exist and becomes a no-op.
    """
    _STACK_LOCK.parent.mkdir(parents=True, exist_ok=True)
    with open(_STACK_LOCK, "w") as lock_fh:
        fcntl.flock(lock_fh, fcntl.LOCK_EX)
        try:
            _run(["docker", "compose", "-f", str(COMPOSE_FILE), "up", "-d"])
            if fresh:
                _install_fresh()
            _wait_for_healthy()
        finally:
            fcntl.flock(lock_fh, fcntl.LOCK_UN)


def start() -> None:
    _ensure_docker_image()
    _ensure_rclone_mount()
    # Create the worker-scoped Drive folder so the SAF picker can
    # select it from the start and the sync-lock.json has a stable
    # Drive ID across the session (the local FUSE mkdir alone does
    # not persist on Drive).
    if DRIVE_SUBDIR:
        _run(["rclone", "mkdir", "--recursive", _worker_remote_path()], check=False)

    if not NCDATA_MOUNT_POINT.exists() or not (NCDATA_MOUNT_POINT / "config" / "config.php").exists():
        _clean_nc_data()
        fresh = True
    else:
        fresh = False

    logger.info("Starting Nextcloud stack...")
    _start_stack(fresh)

    if not _container_mount_ok():
        raise RuntimeError("Nextcloud container cannot access /mnt/gdrive after restart")

    # Clear rate limiter for localhost — tests hit WebDAV rapidly
    for ip in ("127.0.0.1", "::1"):
        _occ(["security:bruteforce:reset", ip], check=False)

    # Disable password_policy so testpass123 is accepted (runs every time,
    # even on reuse, in case a partial setup left testuser in a bad state)
    _occ(["app:disable", "password_policy"], check=False)

    # Disable transactional file locking for the test stack only (laptop
    # Nextcloud at /tmp/nc_data, not phone prod Drive). The test bridge
    # App → Nextcloud client DocumentsProvider → Nextcloud local@ /mnt/gdrive
    # → rclone → Drive does create+PUT 0-byte then PUT data to the same path
    # ~1s apart; with DBLockingProvider 3600s that second PUT gets 423 LOCKED
    # and leaves 0-byte ghosts (test_large). Prod App → Drive has no DB lock,
    # so disabling here makes CI prod-like. Also disable the DAV manual-lock
    # app (files_lock) which is not needed for PicPocket's sync-lock.json.
    _occ(["config:system:set", "filelocking.enabled", "--value", "false", "--type", "boolean"], check=False)
    _occ(["app:disable", "files_lock"], check=False)

    if _is_already_setup() and not fresh:
        logger.info("Nextcloud already configured, reusing existing data")
        return

    result = _occ(["app:enable", "files_external"])
    if result.returncode != 0:
        logger.error(_fail("Failed to enable files_external app", result))
        return
    logger.info("files_external app enabled")

    result = _run(
        ["docker", "exec", "-e", "OC_PASS=testpass123", NEXTCLOUD_CONTAINER]
        + OCC + ["user:add", "--password-from-env", "testuser"],
        check=False,
    )
    if result.returncode != 0:
        logger.error(_fail("Failed to create testuser", result))
        return
    if not _user_exists("testuser"):
        logger.error("testuser not found after successful user:add command")
        return
    logger.info("testuser created")

    for domain, value in [("0", "localhost"), ("1", "10.0.2.2")]:
        result = _occ(["config:system:set", "trusted_domains", domain, "--value", value])
        if result.returncode != 0:
            logger.error(_fail(f"Failed to set trusted_domain {domain}={value}", result))
            return
    logger.info("Trusted domains set: localhost, 10.0.2.2")

    _configure_local_mount()

    # Verify testuser can see PicPocketTest via WebDAV
    result = _run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
         "-u", "testuser:testpass123",
         "-X", "PROPFIND",
         "http://localhost:8080/remote.php/dav/files/testuser/PicPocketTest",
         "-H", "Depth: 0"],
        check=False,
    )
    if result.stdout.strip() == "207":
        logger.info("PicPocketTest accessible via WebDAV (HTTP 207)")
    else:
        logger.warning(
            "PicPocketTest WebDAV check returned HTTP %s (expected 207)",
            result.stdout.strip(),
        )

    logger.info("Nextcloud infrastructure ready")


def stop() -> None:
    result = _run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "down"],
        check=False,
    )
    if result.returncode != 0:
        logger.warning("docker compose down returned %d: %s", result.returncode, result.stderr.strip())
    else:
        logger.info("Nextcloud stack stopped")


def reset_bruteforce() -> None:
    """Reset Nextcloud rate limiter for localhost IPs."""
    for ip in ("127.0.0.1", "::1"):
        _occ(["security:bruteforce:reset", ip], check=False)
    logger.info("Bruteforce protection reset for localhost")


def reset_data() -> None:
    """Full reset — cleans all data for a clean test state."""
    result = _run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "down", "-v"],
        check=False,
    )
    if result.returncode != 0:
        logger.warning("docker compose down -v returned %d: %s", result.returncode, result.stderr.strip())
    _clean_nc_data()
    logger.info("Nextcloud data reset complete")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    start()