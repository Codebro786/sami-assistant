package com.sami.assistant.actions

import android.view.accessibility.AccessibilityNodeInfo
import com.sami.assistant.commands.Command
import com.sami.assistant.system.SamiAccessibilityService

/**
 * Screen interaction through the AccessibilityService. Node actions first; a coordinate
 * gesture is used only on the bounds of a node we positively identified. Ambiguous targets
 * are reported back instead of guessed.
 */
class ScreenAction : Action<Command.Screen> {
    override val id = "screen.control"
    override val description = "Back, recents, notification shade, screenshot, scroll, tap, read screen"
    override val requiredPermissions = listOf("AccessibilityService (Settings › Accessibility › Sami)")
    override val risk = Risk.MEDIUM

    override suspend fun execute(command: Command.Screen): ActionResult {
        val svc = SamiAccessibilityService.instance
            ?: return ActionResult.Unsupported("Screen control is off. Turn on Sami in Accessibility settings — the Permissions page has a shortcut.")
        return when (command) {
            is Command.Global -> if (svc.global(command.op)) ActionResult.Success(globalSpeech(command.op))
                else ActionResult.Failure("Android refused that action.")
            is Command.Scroll -> scroll(svc, command.dir)
            is Command.Tap -> tap(svc, command)
            Command.ReadScreen -> read(svc)
        }
    }

    private fun globalSpeech(op: Command.GlobalOp) = when (op) {
        Command.GlobalOp.BACK -> "Going back."; Command.GlobalOp.RECENTS -> "Recent apps."
        Command.GlobalOp.NOTIFICATIONS -> "Notifications."; Command.GlobalOp.QUICK_SETTINGS -> "Quick settings."
        Command.GlobalOp.SCREENSHOT -> "Screenshot taken."; Command.GlobalOp.LOCK -> "Locking."
    }

    private suspend fun scroll(svc: SamiAccessibilityService, dir: Command.ScrollDir): ActionResult {
        val root = svc.targetRoot() ?: return ActionResult.Failure("I can't see the app on screen.")
        val list = svc.largestScrollable(root)
        if (list != null && (dir == Command.ScrollDir.DOWN || dir == Command.ScrollDir.UP)) {
            val action = if (dir == Command.ScrollDir.DOWN) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (list.performAction(action)) return ActionResult.Success(if (dir == Command.ScrollDir.DOWN) "Scrolled down." else "Scrolled up.")
        }
        return if (svc.swipe(dir)) ActionResult.Success("Swiped ${dir.name.lowercase()}.")
        else ActionResult.Failure("Scrolling didn't work here.")
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), " ").replace(Regex("""\s+"""), " ").trim()

    private suspend fun tap(svc: SamiAccessibilityService, cmd: Command.Tap): ActionResult {
        val root = svc.targetRoot() ?: return ActionResult.Failure("I can't see the app on screen.")
        val verb = if (cmd.longPress) "Long-pressed" else "Tapped"

        if (cmd.ordinal != null) {
            val list = svc.largestScrollable(root) ?: return ActionResult.Failure("I don't see a list on this screen.")
            val items = (0 until list.childCount).mapNotNull { list.getChild(it) }
                .filter { it.isVisibleToUser }
                .mapNotNull { child -> (svc.clickableTarget(child, cmd.longPress) ?: firstClickableDescendant(svc, child, cmd.longPress))?.let { child to it } }
                .sortedBy { svc.bounds(it.first).top }
            if (items.isEmpty()) return ActionResult.Failure("I don't see any items I can tap.")
            val idx = if (cmd.ordinal == -1) items.lastIndex else cmd.ordinal - 1
            val (child, target) = items.getOrNull(idx) ?: return ActionResult.Failure("I only see ${items.size} items.")
            val label = describe(svc, child)
            return if (click(target, cmd.longPress)) ActionResult.Success("$verb ${label ?: "item ${idx + 1}"}.")
            else ActionResult.Failure("That item didn't respond.")
        }

        val q = norm(cmd.target ?: return ActionResult.Failure("What should I tap?"))
        val scored = svc.allNodes(root).filter { it.isVisibleToUser }.mapNotNull { n ->
            val l = norm(svc.labelOf(n)); if (l.isEmpty()) return@mapNotNull null
            val score = when {
                l == q -> 3
                l.startsWith("$q ") || l.endsWith(" $q") -> 2
                q.length >= 3 && l.contains(q) -> 1
                else -> 0
            }
            if (score > 0) n to score else null
        }
        if (scored.isEmpty()) return ActionResult.Failure("I don't see \"${cmd.target}\" on the screen.")
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        // Collapse candidates that resolve to the same clickable element.
        val targets = top.map { (n, _) -> n to svc.clickableTarget(n, cmd.longPress) }
            .distinctBy { (n, t) -> (t ?: n).let { svc.bounds(it).flattenToString() } }
        if (targets.size > 1) return ActionResult.Failure("I see ${targets.size} matches for \"${cmd.target}\". Please be more specific.")
        val (node, target) = targets.single()
        if (target != null && click(target, cmd.longPress)) return ActionResult.Success("$verb ${svc.labelOf(node)}.")
        // Identified the element but nothing clickable in its ancestry: tap its centre.
        val r = svc.bounds(node)
        if (r.isEmpty) return ActionResult.Failure("\"${cmd.target}\" isn't tappable.")
        return if (svc.tapAt(r.exactCenterX(), r.exactCenterY(), cmd.longPress)) ActionResult.Success("$verb ${svc.labelOf(node)}.")
        else ActionResult.Failure("The tap was cancelled.")
    }

    private fun firstClickableDescendant(svc: SamiAccessibilityService, n: AccessibilityNodeInfo, long: Boolean) =
        svc.allNodes(n).firstOrNull { if (long) it.isLongClickable else it.isClickable }

    private fun describe(svc: SamiAccessibilityService, n: AccessibilityNodeInfo) =
        svc.allNodes(n).map { svc.labelOf(it) }.firstOrNull { it.length in 2..80 }

    private fun click(n: AccessibilityNodeInfo, long: Boolean) =
        n.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)

    private fun read(svc: SamiAccessibilityService): ActionResult {
        val root = svc.targetRoot() ?: return ActionResult.Failure("I can't see the app on screen.")
        val texts = svc.allNodes(root).filter { it.isVisibleToUser }.map { svc.labelOf(it) }
            .filter { it.length > 1 }.distinct().take(12)
        if (texts.isEmpty()) return ActionResult.Success("This screen has no readable text.")
        return ActionResult.Success("On screen: " + texts.joinToString(". "))
    }
}
