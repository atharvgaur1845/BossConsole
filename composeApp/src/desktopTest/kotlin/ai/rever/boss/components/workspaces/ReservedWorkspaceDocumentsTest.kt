package ai.rever.boss.components.workspaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The names the workspace directory reserves for its own records, and the ids whose file name
 * would land on one. Pinned against the constants so a renamed record cannot drift out of the
 * set unnoticed (BossConsole#926).
 */
class ReservedWorkspaceDocumentsTest {
    @Test
    fun `the reserved set is exactly the store's records under every name they are written by`() {
        assertEquals(
            setOf("Space_Themes.json", "Last_Session_Set.json", "Last_Session.json", "last-session.json"),
            WorkspaceFileManagerCommon.reservedDocumentFileNames(),
        )
    }

    @Test
    fun `an id is refused when its file name is a record, whatever spelling reaches the same file`() {
        val colliding =
            listOf("Space_Themes", "Space Themes", "Space/Themes", "Last_Session_Set", "Last Session", "last-session")
        for (id in colliding) {
            assertTrue(WorkspaceFileManagerCommon.isReservedDocumentId(id), id)
        }
        for (id in listOf("workspace-1788834771145", "Space_Themes_2", "my-last-session", "Themes")) {
            assertFalse(WorkspaceFileManagerCommon.isReservedDocumentId(id), id)
        }
    }
}
