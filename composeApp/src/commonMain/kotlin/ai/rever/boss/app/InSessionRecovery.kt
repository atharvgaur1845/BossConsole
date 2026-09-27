package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.workspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The layout watcher's write, once its settle delay has passed: keep the crash-recovery files
 * current, from the one window that owns them.
 *
 * Only [LastSessionCoordinator.ownsSessionRecord]'s window writes, and it writes BOTH files, the
 * same pair the coordinator writes at shutdown. [set] is built before anything is written, on the
 * caller's dispatcher, because it reads the window's live Compose state. Even a single Space
 * retains its identity in the set; a null set removes a stale recovery set instead of letting
 * it outrank the record (see `sessionSetOf`).
 *
 * The pair is [LastSessionCoordinator.writeInSession]: one blocking, non-suspending call, set
 * first, under the lock the shutdown write takes. Being one non-suspending call is what keeps the
 * pair whole: cancellation is only delivered at a suspension point, so once it has started, closing
 * the window cannot leave a fresh record beside the stale set restore prefers, which is the bug this
 * exists to fix. [NonCancellable] covers the moment before that: Compose cancels a closing window's
 * watcher before the coordinator hears of the close, and without it a cancel that landed before the
 * IO dispatch would drop the window's last change unwritten.
 *
 * Nothing throws out of here: not the ownership check (it runs every live window's `canSave`), not
 * the set builder, not the pair. The caller is the layout watcher, and an exception escaping into it
 * would end the watcher for the rest of the window's life. A failure is logged through
 * [LastSessionCoordinator.reportInSessionFailure] - at warn once per run of failures, at debug after
 * that - and answered false. Cancellation still propagates.
 *
 * Returns whether this window wrote the record. Any other window writes nothing: its layout was
 * never what a restart brings back, and writing it replaced the owner's recovery copy.
 */
// A throw from the ownership check (another window's state), the set builder or the pair must
// never end the watcher; cancellation still propagates.
@Suppress("TooGenericExceptionCaught")
internal suspend fun writeInSessionRecovery(
    windowId: String,
    record: LayoutWorkspace,
    set: () -> LastSessionSet?,
    coordinator: LastSessionCoordinator = LastSessionCoordinator.instance,
    manager: WorkspaceManager = workspaceManager,
): Boolean =
    try {
        writeIfOwner(windowId, record, set, coordinator, manager)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        coordinator.reportInSessionFailure(windowId, "In-session recovery write skipped", e)
        false
    }

private suspend fun writeIfOwner(
    windowId: String,
    record: LayoutWorkspace,
    set: () -> LastSessionSet?,
    coordinator: LastSessionCoordinator,
    manager: WorkspaceManager,
): Boolean {
    if (!coordinator.ownsSessionRecord(windowId)) return false
    // Built here, on the caller's dispatcher, because it reads live Compose state. A null result
    // is a real answer (remove the set); a throw lands in the caller's catch and writes nothing.
    val liveSet = set()
    val written =
        withContext(Dispatchers.IO + NonCancellable) {
            coordinator.writeInSession(windowId, record, liveSet)
        }
    if (written) manager.noteLastSessionRecordWritten(record)
    return written
}
