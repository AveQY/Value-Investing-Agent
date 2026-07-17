package com.aweqy.aiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalOperationToolTest {

    @Test
    void executeTerminalCommandReturnsStdout() {
        TerminalOperationTool tool = new TerminalOperationTool();

        String result = tool.executeTerminalCommand("echo hello-terminal", ".", 30, "auto");

        assertTrue(result.contains("status: SUCCESS"));
        assertTrue(result.contains("hello-terminal"));
        assertTrue(result.contains("exitCode: 0"));
    }
}
