package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LAST_SESSION_NAME
import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.isRestorable
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which recovery files a hard kill leaves behind, and who is allowed to have written them.
 *
 * The multi-Space set used to be written only at a clean shutdown, and restore reads it in
 * preference to `Last_Session.json`. So after a session that restored a set, worked, and was then
 * killed, the next launch brought back the clean shutdown BEFORE it and the watcher wrote those
 * older layouts over the fresh record. And every window's watcher wrote the record, so a secondary
 * window's layout could replace the primary's. [writeInSessionRecovery] is the watcher's write:
 * the session record's owner keeps both files current, and no other window writes either.
 *
 * Each "session" is its own [WorkspaceManager] over one directory, which is what a process restart
 * is to these files.
 */
class InSessionRecoveryTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun directory(): File = Files.createTempDirectory("in-session-recovery").toFile().also { dirs += it }

    private fun session(dir: File) = WorkspaceManager(fileManager = WorkspaceFileManager(dir.absolutePath))

    private fun space(
        id: String,
        tab: String,
    ) = LayoutWorkspace(
        id = id,
        name = id,
        description = "d",
        layout =
            SplitConfig.SinglePanel(
                PanelConfig(id = "panel-$id", tabs = listOf(TabConfig(type = "terminal", title = tab))),
            ),
        timestamp = 1_700_000_000_000,
        projectPath = "/tmp/proj",
    )

    private fun record(tab: String) = space(LAST_SESSION_ID, tab).copy(name = LAST_SESSION_NAME)

    private fun set(vararg tabs: Pair<String, String>) =
        LastSessionSet(activeWorkspaceId = tabs.last().first, spaces = tabs.map { (id, tab) -> space(id, tab) })

    private fun titles(set: LastSessionSet?) =
        set?.spaces?.map {
            (it.layout as SplitConfig.SinglePanel)
                .panel.tabs
                .single()
                .title
        }

    /** Session 1 ran two Spaces and quit cleanly: the coordinator wrote both files. */
    private fun cleanShutdown(dir: File) {
        val first = session(dir)
        assertTrue(first.saveLastSessionBlocking(space("a", tab = "old a")))
        assertTrue(first.saveLastSessionSetBlocking(set("b" to "old b", "a" to "old a")))
    }

    private suspend fun recordedTitle(manager: WorkspaceManager): String {
        // The manager loads its list asynchronously at construction; wait for the record.
        val record =
            withTimeout(10_000) {
                manager.workspaces.first { list -> list.any { it.id == LAST_SESSION_ID } }
            }.single { it.id == LAST_SESSION_ID }
        return (record.layout as SplitConfig.SinglePanel)
            .panel.tabs
            .single()
            .title
    }

    private fun coordinator(vararg windows: Pair<String, Boolean>) =
        LastSessionCoordinator(save = { true }).also { coordinator ->
            windows.forEach { (id, primary) -> coordinator.register(id, primary) { record("unused") } }
        }

    @Test
    fun `the owner keeps the set as fresh as the record, so a kill restores the session that died`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)

            // Session 2 restores, works in both Spaces, and its primary window's watcher records
            // it - then the process is killed, so no shutdown write happens.
            val second = session(dir)
            val wrote =
                writeInSessionRecovery(
                    windowId = "primary",
                    record = record(tab = "new a"),
                    set = { set("b" to "new b", "a" to "new a") },
                    coordinator = coordinator("primary" to true),
                    manager = second,
                )
            assertTrue(wrote)

            // Session 3 restores session 2's Spaces, both of them, not session 1's.
            val third = session(dir)
            assertEquals(listOf("new b", "new a"), titles(third.loadLastSessionSet()?.takeIf { isRestorable(it) }))
            assertEquals("new a", recordedTitle(third))
        }

    @Test
    fun `a secondary window writes neither recovery file`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)

            val wrote =
                writeInSessionRecovery(
                    windowId = "secondary",
                    record = record(tab = "secondary layout"),
                    set = { error("a window that does not own the record must not even build a set") },
                    coordinator = coordinator("primary" to true, "secondary" to false),
                    manager = session(dir),
                )

            assertFalse(wrote)
            val next = session(dir)
            assertEquals(listOf("old b", "old a"), titles(next.loadLastSessionSet()))
            assertEquals("old a", recordedTitle(next))
        }

    @Test
    fun `once the primary has closed, the window a shutdown would write for owns the record`() =
        runBlocking<Unit> {
            val dir = directory()
            val coordinator = coordinator("primary" to true, "secondary" to false)
            assertFalse(coordinator.onWindowDisposed("primary"), "a close with another window open writes nothing")

            assertTrue(coordinator.ownsSessionRecord("secondary"))
            assertTrue(
                writeInSessionRecovery("secondary", record("second"), { null }, coordinator, session(dir)),
            )
        }

    @Test
    fun `nobody writes while a live window is protecting a refused restore`() {
        val coordinator = LastSessionCoordinator(save = { true })
        coordinator.register("primary", isFirstWindow = true, canSave = { false }) { record("unused") }
        coordinator.register("secondary", isFirstWindow = false) { record("unused") }

        assertFalse(coordinator.ownsSessionRecord("primary"))
        assertFalse(coordinator.ownsSessionRecord("secondary"))
    }

    @Test
    fun `a window no longer running two Spaces removes the earlier set instead of letting it outrank the record`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)

            writeInSessionRecovery("primary", record("only a"), { null }, coordinator("primary" to true), session(dir))

            val next = session(dir)
            assertNull(next.loadLastSessionSet())
            assertEquals("only a", recordedTitle(next))
        }

    @Test
    fun `a kill before any in-session write still restores every Space from the set`() =
        runBlocking<Unit> {
            val dir = directory()
            cleanShutdown(dir)

            // Session 2 restores and is killed before its watcher has written anything.
            val set = assertNotNull(session(dir).loadLastSessionSet())
            assertEquals(listOf("old b", "old a"), titles(set))
        }
}
