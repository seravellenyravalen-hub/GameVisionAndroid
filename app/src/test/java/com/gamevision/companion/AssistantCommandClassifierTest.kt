package com.gamevision.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCommandClassifierTest {
    @Test fun explicitNaturalCommandsAreActionRequests() {
        assertTrue(AssistantCommandClassifier.isActionCommand("please tap the enemy"))
        assertTrue(AssistantCommandClassifier.isActionCommand("can you open YouTube"))
        assertTrue(AssistantCommandClassifier.isActionCommand("I want you to press that button"))
        assertTrue(AssistantCommandClassifier.isActionCommand("help me scroll down"))
    }

    @Test fun ordinaryQuestionsRemainConversation() {
        assertFalse(AssistantCommandClassifier.isActionCommand("why is anything wrong?"))
        assertFalse(AssistantCommandClassifier.isActionCommand("what is 25 percent of 480?"))
        assertFalse(AssistantCommandClassifier.isActionCommand("tell me what you see"))
    }
}
