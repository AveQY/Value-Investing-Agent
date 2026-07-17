package com.aweqy.aiagent.tools;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StockPriceToolTest {

    private final StockPriceTool tool = new StockPriceTool();

    @Test
    void extractLookupInputShouldStripCommonNoise() {
        assertEquals("腾讯", tool.extractLookupInput("帮我查一下腾讯现在股价多少"));
        assertEquals("AAPL", tool.extractLookupInput("AAPL 现在股价多少"));
    }

    @Test
    void resolveDirectSymbolShouldInferMarketAndProviderSymbol() {
        StockPriceTool.ResolvedStock cn = tool.resolveDirectSymbol("600519");
        StockPriceTool.ResolvedStock hk = tool.resolveDirectSymbol("00700");
        StockPriceTool.ResolvedStock us = tool.resolveDirectSymbol("AAPL");

        assertNotNull(cn);
        assertNotNull(hk);
        assertNotNull(us);
        assertEquals(StockPriceTool.Market.CN, cn.market());
        assertEquals("sh600519", cn.sinaSymbol());
        assertEquals(StockPriceTool.Market.HK, hk.market());
        assertEquals("hk00700", hk.sinaSymbol());
        assertEquals(StockPriceTool.Market.US, us.market());
        assertEquals("gb_aapl", us.sinaSymbol());
    }

    @Test
    void parseQuoteResponseShouldParseAshareSnapshot() {
        StockPriceTool.ResolvedStock stock = tool.resolveDirectSymbol("600519");
        String raw = "var hq_str_sh600519=\"贵州茅台,1403.000,1407.240,1410.890,1413.940,1400.000,1410.890,1410.900,3645139,5127146041.000,164,1410.890,1200,1410.880,500,1410.640,180,1410.620,100,1410.550,300,1410.900,200,1410.910,200,1410.940,100,1410.990,2500,1411.000,2026-04-20,15:00:03,00,\";";

        JSONObject quote = tool.parseQuoteResponse(stock, raw);

        assertEquals("贵州茅台", quote.getStr("name"));
        assertEquals(new BigDecimal("1410.89"), quote.getBigDecimal("price"));
        assertEquals(new BigDecimal("3.65"), quote.getBigDecimal("change"));
        assertEquals("2026-04-20 15:00:03", quote.getStr("quoteTime"));
    }
}
