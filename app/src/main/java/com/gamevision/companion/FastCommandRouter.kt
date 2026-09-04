package com.gamevision.companion

/** Local deterministic parser. It only classifies explicit user commands. */
object FastCommandRouter {
    fun parse(input: String): AutomationAction? {
        val value = input.trim()
        val lower = value.lowercase()
        if (value.isBlank()) return null
        fun action(type: String, text: String = "", waitMs: Long = 250L) = AutomationAction(
            type = type, x = 0, y = 0, x2 = 0, y2 = 0, text = text,
            durationMs = 0L, waitMs = waitMs, reason = "Deterministic local command",
            confidence = 100, verify = true, stopReason = ""
        )
        return when {
            lower.matches(Regex("^(go )?back$")) -> action("BACK")
            lower.matches(Regex("^(go )?home$")) -> action("HOME")
            lower.matches(Regex("^(open )?(recent apps|recents|recent)$")) -> action("RECENTS")
            lower.matches(Regex("^(open )?notifications?$")) -> action("NOTIFICATIONS")
            lower.matches(Regex("^(open )?quick settings$")) -> action("QUICK_SETTINGS")
            lower.matches(Regex("^(open|launch|start|run)\\s+.+$")) -> {
                val target = value.replaceFirst(Regex("^(open|launch|start|run)\\s+", RegexOption.IGNORE_CASE), "").trim()
                action("OPEN_APP", target, 150L)
            }
            lower.matches(Regex("^(scroll|swipe)\\s+(up|down|left|right)$")) -> {
                val direction = lower.substringAfterLast(' ')
                val (x, y, x2, y2) = when (direction) {
                    "up" -> intArrayOf(500, 750, 500, 250)
                    "down" -> intArrayOf(500, 250, 500, 750)
                    "left" -> intArrayOf(750, 500, 250, 500)
                    else -> intArrayOf(250, 500, 750, 500)
                }
                AutomationAction("SWIPE", x, y, x2, y2, "", 450L, 650L, "Deterministic local scroll", 100, true, "")
            }
            lower == "stop" || lower == "cancel" -> action("STOP")
            value.matches(Regex("^(tap|click|press|touch)\\s+.+$", RegexOption.IGNORE_CASE)) -> action("TAP_TARGET", value.replaceFirst(Regex("^(tap|click|press|touch)\\s+", RegexOption.IGNORE_CASE), ""))
            value.matches(Regex("^double tap\\s+.+$", RegexOption.IGNORE_CASE)) -> action("DOUBLE_TAP_TARGET", value.replaceFirst(Regex("^double tap\\s+", RegexOption.IGNORE_CASE), ""))
            value.matches(Regex("^(long press|hold)\\s+.+$", RegexOption.IGNORE_CASE)) -> action("LONG_PRESS_TARGET", value.replaceFirst(Regex("^(long press|hold)\\s+", RegexOption.IGNORE_CASE), ""), 650L)
            value.matches(Regex("^(type|enter|write)\\s+.+$", RegexOption.IGNORE_CASE)) -> action("TYPE_TEXT", value.replaceFirst(Regex("^(type|enter|write)\\s+", RegexOption.IGNORE_CASE), ""), 500L)
            lower.matches(Regex("^wait\\s+\\d+\\s*(ms|milliseconds?)?$")) -> {
                val ms = Regex("\\d+").find(lower)?.value?.toLongOrNull()?.coerceIn(0L, 5000L) ?: 0L
                action("WAIT", waitMs = ms)
            }
            else -> null
        }
    }
}
