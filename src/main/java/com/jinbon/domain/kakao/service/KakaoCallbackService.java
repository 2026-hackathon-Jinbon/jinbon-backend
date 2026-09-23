package com.jinbon.domain.kakao.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class KakaoCallbackService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    @Autowired
    public KakaoCallbackService(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(16),
                        Thread.ofPlatform().name("kakao-verify-", 0).factory()));
    }

    KakaoCallbackService(ObjectMapper objectMapper, HttpClient httpClient, ExecutorService executor) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.executor = executor;
    }

    public void submit(String callbackUrl, Supplier<Map<String, Object>> verification) {
        URI uri = URI.create(callbackUrl);
        // 공개 스킬 API에 임의 URL을 보내 내부망으로 요청하게 만들 수 없도록 제한한다.
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"bot-api.kakao.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("Invalid Kakao callback URL");
        }
        executor.execute(() -> send(uri, verification));
    }

    private void send(URI uri, Supplier<Map<String, Object>> verification) {
        try {
            Map<String, Object> result = verification.get();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(result)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Kakao callback delivery failed - httpStatus={}", response.statusCode());
                return;
            }
            var body = objectMapper.readTree(response.body());
            if ("SUCCESS".equals(body.path("status").asText())) {
                log.info("Kakao callback delivered - taskId={}", body.path("taskId").asText());
            } else {
                log.warn("Kakao callback rejected - status={}, message={}",
                        body.path("status").asText(), body.path("message").asText());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Kakao callback interrupted");
        } catch (Exception e) {
            // 콜백 URL에는 일회용 토큰이 있으므로 예외 메시지나 URL을 기록하지 않는다.
            log.warn("Kakao callback failed - errorType={}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        httpClient.close();
    }
}
