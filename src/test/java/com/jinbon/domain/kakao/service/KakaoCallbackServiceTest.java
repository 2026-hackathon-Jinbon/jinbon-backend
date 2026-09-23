package com.jinbon.domain.kakao.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KakaoCallbackServiceTest {
    private final HttpClient httpClient = mock(HttpClient.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final KakaoCallbackService service = new KakaoCallbackService(new JsonMapper(), httpClient, executor);
    private static final String CALLBACK = "https://bot-api.kakao.com/callback/test-token";

    @Test void queuesVerificationAndPostsItsActualResultExactlyOnce() throws Exception {
        @SuppressWarnings("unchecked")
        Supplier<Map<String, Object>> verification = mock(Supplier.class);
        Map<String, Object> result = Map.of("version", "2.0", "template",
                Map.of("outputs", List.of(Map.of("simpleText", Map.of("text", "검증 결과")))));
        when(verification.get()).thenReturn(result);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":\"SUCCESS\",\"taskId\":\"test\"}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        service.submit(CALLBACK, verification);
        verifyNoInteractions(verification, httpClient);
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(request.capture(), any());
        assertThat(request.getValue().uri().toString()).isEqualTo(CALLBACK);
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().headers().firstValue("Content-Type")).contains("application/json");
        assertThat(new JsonMapper().readTree(readBody(request.getValue())))
                .isEqualTo(new JsonMapper().valueToTree(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://bot-api.kakao.com/callback/token", "https://127.0.0.1/callback",
            "https://bot-api.kakao.com.attacker.example/callback", "https://attacker.example/callback",
            "https://bot-api.kakao.com@127.0.0.1/callback", "https://user@bot-api.kakao.com/callback",
            "https://bot-api.kakao.com:8080/callback", "https://bot-api.kakao.com/callback#fragment"})
    void rejectsUntrustedCallbackDestinationsBeforeQueueing(String url) {
        assertThatThrownBy(() -> service.submit(url, Map::of)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(executor, httpClient);
    }

    @Test void doesNotRetryOneTimeCallbackWhenDeliveryFails() throws Exception {
        doAnswer(invocation -> { invocation.getArgument(0, Runnable.class).run(); return null; })
                .when(executor).execute(any());
        doThrow(new java.io.IOException("network failure")).when(httpClient).send(any(), any());
        assertThatCode(() -> service.submit(CALLBACK, Map::of)).doesNotThrowAnyException();
        verify(httpClient, times(1)).send(any(), any());
    }

    private String readBody(HttpRequest request) {
        var bytes = new java.io.ByteArrayOutputStream();
        var done = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return done.join();
    }
}
