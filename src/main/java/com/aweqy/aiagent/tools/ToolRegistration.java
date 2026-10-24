package com.aweqy.aiagent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具注册类，负责将所有工具注册到 Spring 容器中，以便在应用中使用
 */
@Configuration
public class ToolRegistration {

    @Bean
    public ToolCallback[] allTools(PDFGenerationTool pdfGenerationTool) {
        FileOperationTool fileOperationTool = new FileOperationTool();
        CurrentTimeTool currentTimeTool = new CurrentTimeTool();
        WebSearchTool webSearchTool = new WebSearchTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        StockPriceTool stockPriceTool = new StockPriceTool();
        AShareFinancialReportTool aShareFinancialReportTool = new AShareFinancialReportTool();
        StockHistoryTool stockHistoryTool = new StockHistoryTool();
        MarketIndexTool marketIndexTool = new MarketIndexTool();
        FundNavTool fundNavTool = new FundNavTool();
        ExchangeRateTool exchangeRateTool = new ExchangeRateTool();
        return ToolCallbacks.from(
                fileOperationTool,
                currentTimeTool,
                stockPriceTool,
                stockHistoryTool,
                marketIndexTool,
                fundNavTool,
                exchangeRateTool,
                aShareFinancialReportTool,
                webSearchTool,
                resourceDownloadTool,
                terminalOperationTool,
                pdfGenerationTool
        );
    }
}
