package com.aweqy.aiagent.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    @Test
    void returnsErrorJsonWhenQueryIsBlank() {
        WebSearchTool tool = new WebSearchTool();

        String result = tool.searchInformation("   ");
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("ERROR", json.getStr("query"));
        assertTrue(json.getJSONArray("results").isEmpty());
    }
}
