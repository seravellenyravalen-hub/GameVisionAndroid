package com.gamevision.companion

import java.util.Locale

/** Pure, deterministic routing guard shared by text and voice commands. */
object AssistantCommandClassifier {
    fun isActionCommand(text: String): Boolean {
        val value = text.trim().lowercase(Locale.US)
        if (value.isBlank()) return false
        val question = value.matches(Regex("^(what|why|how|when|where|who|which|can you explain|tell me|is it|are you|do you know)\\b.*")) || value.endsWith("?")
        if (question && !value.matches(Regex("^(can you|could you|please|would you|will you|are you able to)\\b.*\\b(open|launch|tap|click|press|hold|swipe|scroll|type|enter|select|choose|close|go|start|stop|play|send|reply|find|search|turn on|turn off|enable|disable|move|do|perform|execute|make)\\b.*"))) return false
        val commandLead = Regex("^(please\\s+|can you\\s+|could you\\s+|would you\\s+|will you\\s+|i want you to\\s+|i need you to\\s+|help me\\s+|go ahead and\\s+)?(tap|click|press|touch|hold|long press|double tap|swipe|scroll|drag|drop|type|enter|write|open|launch|start|run|close|select|choose|find|search|send|reply|go back|go home|navigate|turn on|turn off|enable|disable|move|play|stop|do|perform|execute|make|use|check|look at|continue)\\b.*")
        return commandLead.matches(value) || value.contains(" for me") || value.startsWith("i want you to ") || value.startsWith("i need you to ") || value.startsWith("help me ")
    }
}
