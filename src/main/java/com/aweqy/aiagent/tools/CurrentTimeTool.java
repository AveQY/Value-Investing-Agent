package com.aweqy.aiagent.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 当前时间查询工具，固定返回北京时间，避免模型自行臆测相对时间边界。
 */
public class CurrentTimeTool {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(
            name = "Get current time",
            description = "Query the current date and time in Beijing time only. " +
                    "Use this before answering questions involving now, today, yesterday, tomorrow, this week, " +
                    "or current quarter. This tool always returns Asia/Shanghai and ignores any other timezone hint."
    )
    public String getCurrentTime(
            @ToolParam(description = "Optional time-related hint. The tool will ignore timezone hints and always return Beijing time.")
            String timezoneHint) {

        String normalizedHint = StringUtils.trimWhitespace(timezoneHint);
        ZonedDateTime utcNow = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime serverNow = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime effectiveNow = utcNow.withZoneSameInstant(DEFAULT_ZONE);

        JSONObject result = new JSONObject();
        result.set("status", "SUCCESS");
        result.set("input", normalizedHint);
        result.set("timezoneResolved", false);
        result.set("timezone", effectiveNow.getZone().getId());
        result.set("currentTime", DISPLAY_TIME_FORMATTER.format(effectiveNow));
        result.set("isoTime", effectiveNow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        result.set("offset", effectiveNow.getOffset().getId());
        result.set("dayOfWeek", effectiveNow.getDayOfWeek().toString());
        result.set("epochMillis", effectiveNow.toInstant().toEpochMilli());
        result.set("serverTimezone", serverNow.getZone().getId());
        result.set("serverTime", DISPLAY_TIME_FORMATTER.format(serverNow));
        result.set("serverIsoTime", serverNow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        result.set("utcTime", utcNow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        result.set("resolutionSource", "beijing-only");
        result.set("resolutionNote", buildResolutionNote(normalizedHint));
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String buildResolutionNote(String timezoneHint) {
        if (!StringUtils.hasText(timezoneHint)) {
            return "该工具固定返回北京时间（Asia/Shanghai）。回答时请明确说明所用时区。";
        }
        return "该工具固定返回北京时间（Asia/Shanghai），已忽略输入提示：%s。回答时请明确说明所用时区。"
                .formatted(timezoneHint);
    }
}
