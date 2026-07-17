package com.aweqy.aiagent.model;

public record ToolLoopLimit(int maxRounds,
                            int maxCallsPerRound,
                            int maxTotalCalls) {
}
