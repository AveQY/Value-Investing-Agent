package com.aweqy.aiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 自定义日志 Advisor
 */
@Slf4j
public class MyLoggerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private void logRequest(AdvisedRequest request) {
        log.info("AI Request: {}", request.userText());
    }

    private void logResponse(ChatResponse response) {
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            log.info("AI Response: {}", response.getResult().getOutput().getText());
        }
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        logRequest(request);
        AdvisedResponse response = chain.nextAroundCall(request);
        logResponse(response.response());
        return response;
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        logRequest(request);
        return chain.nextAroundStream(request)
                .doOnNext(response -> logResponse(response.response()));
    }
}