package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.workspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The layout watcher's write, once its settle delay has passed: keep the crash-recovery files
 * current, from the one window that owns them.
 *
 * Only [LastSessionCoordinator.ownsSessionRecord]'s window writes, and it writes BOTH files, the
 * same pair the coordinator writes at shutdown. [set] is built before anything is written, on the
 * caller's dispatcher, because it reads the window's live Compose state; it is null for a window
 * running fewer than two Spaces, which removes a set an earlier session left rather than letting
 * it outrank the record (see `sessionSetOf`).
 *
 * The pair is [LastSessionCoordinator.writeInSession]: one blocking call, set first, under the
 * lock the shutdown write takes, so the two writes cannot interleave with that one. It is not
 * cancellable either: closing a window cancels its watcher before the coordinator hears of the
 * close, and a pair cut in half - a fresh record beside the stale set restore prefers - is the bug
 * this exists to fix.
 *
 * Returns whether this window wrote the record. Any other window writes nothing: its layout was
 * never what a restart brings back, and writing it replaced the owner's recovery copy.
 */
internal suspend fun writeInSessionRecovery(
    windowId: String,
    record: LayoutWorkspace,
    set: () -> LastSessionSet?,
    coordinator: LastSessionCoordinator = LastSessionCoordinator.instance,
    manager: WorkspaceManager = workspaceManager,
): Boolean {
    if (!coordinator.ownsSessionRecord(windowId)) return false
    val liveSet = set()
    val written =
        withContext(Dispatchers.IO + NonCancellable) {
            coordinator.writeInSession(windowId, record, liveSet)
        }
    if (written) manager.noteLastSessionRecordWritten(record)
    return written
}
