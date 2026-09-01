package com.gs.monolito.auth.filter;

import com.gs.monolito.common.security.ClientIp;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter para POST /api/auth/login (Token Bucket, Bucket4j).
 * 5 intentos por minuto por IP; al sexto → 429.
 */
public class LoginRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitInterceptor.class);
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final int CAPACITY = 5;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (!HttpMethod.POST.matches(request.getMethod()) ||
            !LOGIN_PATH.equals(request.getRequestURI())) {
            return true;
        }

        String ip = ClientIp.resolve(request);
        Bucket bucket = buckets.computeIfAbsent(ip, this::newBucket);

        if (bucket.tryConsume(1)) {
            long remaining = bucket.getAvailableTokens();
            response.addHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            return true;
        }

        log.warn("[GS-SECURITY] Rate limit excedido para IP: {}", ip);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"error\": \"Demasiados intentos de login. Esperá 1 minuto antes de volver a intentar.\"}"
        );
        return false;
    }

    private Bucket newBucket(String ip) {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(CAPACITY)
                .refillGreedy(CAPACITY, REFILL_PERIOD)
                .build())
            .build();
    }
}
