package com.aweqy.aiagent.controller;

import com.aweqy.aiagent.app.FundApp;
import com.aweqy.aiagent.service.ChatHistoryService;
import com.aweqy.aiagent.service.ChatModelRegistryService;
import com.aweqy.aiagent.service.ReportDocumentService;
import com.aweqy.aiagent.service.ReportTaskService;
import com.aweqy.aiagent.service.SessionLockService;
import com.aweqy.aiagent.service.ValueAnalysisService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class FundAppControllerSseTest {

    @Test
    void shouldNotDisposeUpstreamSubscriptionWhenClientDisconnectsDuringSend() throws Exception {
        FundAppController controller = new FundAppController(
                Mockito.mock(FundApp.class),
                Mockito.mock(ChatHistoryService.class),
                Mockito.mock(ChatModelRegistryService.class),
                Mockito.mock(ValueAnalysisService.class),
                Mockito.mock(ReportDocumentService.class),
                Mockito.mock(ReportTaskService.class),
                Mockito.mock(SessionLockService.class)
        );

        Disposable disposable = Mockito.mock(Disposable.class);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>(disposable);
        AtomicBoolean connectionClosed = new AtomicBoolean(false);
        SseEmitter emitter = new ClientDisconnectingEmitter();

        Method sendChunk = FundAppController.class.getDeclaredMethod(
                "sendChunk",
                SseEmitter.class,
                String.class,
                AtomicReference.class,
                AtomicBoolean.class
        );
        sendChunk.setAccessible(true);
        sendChunk.invoke(controller, emitter, "partial answer", subscriptionRef, connectionClosed);

        Assertions.assertTrue(connectionClosed.get(), "检测到客户端断连后，应立即停止继续向前端写入");
        Mockito.verify(disposable, Mockito.never()).dispose();
    }

    private static final class ClientDisconnectingEmitter extends SseEmitter {

        ClientDisconnectingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            throw new IOException("你的主机中的软件中止了一个已建立的连接。");
        }
    }
}
