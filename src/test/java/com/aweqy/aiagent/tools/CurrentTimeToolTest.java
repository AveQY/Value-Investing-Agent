package com.aweqy.aiagent.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTimeToolTest {

    private final CurrentTimeTool tool = new CurrentTimeTool();

    @Test
    void shouldAlwaysReturnBeijingTimeWhenHintIsMissing() {
        String result = tool.getCurrentTime(null);
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("SUCCESS", json.getStr("status"));
        assertFalse(json.getBool("timezoneResolved"));
        assertEquals("Asia/Shanghai", json.getStr("timezone"));
        assertEquals("beijing-only", json.getStr("resolutionSource"));
        assertTrue(json.getStr("resolutionNote").contains("固定返回北京时间"));
        assertTrue(json.getStr("currentTime").length() >= 19);
    }

    @Test
    void shouldStillReturnBeijingTimeWhenTimezoneHintIsProvided() {
        String result = tool.getCurrentTime("America/New_York");
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("SUCCESS", json.getStr("status"));
        assertFalse(json.getBool("timezoneResolved"));
        assertEquals("America/New_York", json.getStr("input"));
        assertEquals("Asia/Shanghai", json.getStr("timezone"));
        assertEquals("beijing-only", json.getStr("resolutionSource"));
        assertTrue(json.getStr("resolutionNote").contains("已忽略输入提示"));
    }

    @Test
    void shouldKeepUtcTimestampForReference() {
        String result = tool.getCurrentTime("UTC");
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("SUCCESS", json.getStr("status"));
        assertEquals("Asia/Shanghai", json.getStr("timezone"));
        assertTrue(json.containsKey("utcTime"));
        assertTrue(json.containsKey("serverTime"));
    }
}
