package com.greenharborlabs.paygate.reference.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.greenharborlabs.paygate.reference.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private static final String RATE_LIMITED_CODE = "RATE_LIMITED";
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final Cache<String, Bucket> buckets;
  private final Map<RouteLimit, Bandwidth> bandwidths = new ConcurrentHashMap<>();

  public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.buckets =
        Caffeine.newBuilder()
            .expireAfterAccess(properties.bucketTtl())
            .maximumSize(100_000)
            .build();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    RouteLimit routeLimit = RouteLimit.fromPath(request.getRequestURI(), properties);
    String key = routeLimit.name() + ":" + clientIp(request);
    Bucket bucket = buckets.get(key, ignored -> newBucket(routeLimit));
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

    addRateLimitHeaders(response, routeLimit.limit(), probe);
    if (probe.isConsumed()) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(waitSeconds(probe)));

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded.");
    problem.setTitle("RATE LIMITED");
    problem.setType(URI.create("https://paygate-reference.greenharborlabs.com/problems/rate_limited"));
    problem.setProperty("code", RATE_LIMITED_CODE);
    problem.setProperty("retryable", true);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }

  private Bucket newBucket(RouteLimit routeLimit) {
    return Bucket.builder().addLimit(bandwidth(routeLimit)).build();
  }

  private Bandwidth bandwidth(RouteLimit routeLimit) {
    return bandwidths.computeIfAbsent(
        routeLimit,
        limit -> Bandwidth.builder().capacity(limit.limit()).refillGreedy(limit.limit(), WINDOW).build());
  }

  private void addRateLimitHeaders(HttpServletResponse response, long limit, ConsumptionProbe probe) {
    response.setHeader("RateLimit-Limit", String.valueOf(limit));
    response.setHeader("RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));
    response.setHeader("RateLimit-Reset", String.valueOf(waitSeconds(probe)));
  }

  private long waitSeconds(ConsumptionProbe probe) {
    long nanos = probe.getNanosToWaitForRefill();
    if (nanos <= 0) return 0;
    return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos + 999_999_999L));
  }

  private String clientIp(HttpServletRequest request) {
    String flyClientIp = request.getHeader("Fly-Client-IP");
    if (flyClientIp != null && !flyClientIp.isBlank()) {
      return flyClientIp.trim();
    }
    String forwarded = firstForwardedFor(request.getHeader("Forwarded"));
    if (!forwarded.isBlank()) return forwarded;
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isBlank()) {
      return xForwardedFor.split(",", 2)[0].trim();
    }
    return request.getRemoteAddr();
  }

  private String firstForwardedFor(String header) {
    if (header == null || header.isBlank()) return "";
    for (String part : header.split(";")) {
      String trimmed = part.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("for=")) {
        return trimmed.substring(4).replace("\"", "").trim();
      }
    }
    return "";
  }

  private record RouteLimit(String name, long limit) {
    private static RouteLimit fromPath(String path, RateLimitProperties properties) {
      if (path.equals("/api/v1/catalog")) {
        return new RouteLimit("catalog", properties.catalogPerMinute());
      }
      if (path.equals("/api/v1/verification/keys")) {
        return new RouteLimit("keys", properties.keysPerMinute());
      }
      if (path.equals("/api/v1/trust/verify")) {
        return new RouteLimit("verify", properties.verifyPerMinute());
      }
      if (path.equals("/api/v1/trust/report")) {
        return new RouteLimit("report", properties.reportPerMinute());
      }
      if (path.equals("/api/v1/trust/quote")) {
        return new RouteLimit("quote", properties.quotePerMinute());
      }
      return new RouteLimit("api", properties.quotePerMinute());
    }
  }
}
