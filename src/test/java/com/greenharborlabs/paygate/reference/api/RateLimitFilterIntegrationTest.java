package com.greenharborlabs.paygate.reference.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.PaygateReferenceApplication;
import com.greenharborlabs.paygate.reference.dns.AddressClassifier;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = {PaygateReferenceApplication.class, RateLimitFilterIntegrationTest.TestOverrides.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "reference.rate-limit.enabled=true",
      "reference.rate-limit.catalog-per-minute=1",
      "reference.rate-limit.keys-per-minute=1",
      "reference.rate-limit.quote-per-minute=2",
      "reference.rate-limit.verify-per-minute=1",
      "reference.rate-limit.report-per-minute=1",
      "reference.rate-limit.bucket-ttl-minutes=1"
    })
@ActiveProfiles("test")
class RateLimitFilterIntegrationTest {
  @LocalServerPort private int port;

  @Test
  void returnsTooManyRequestsWithRateLimitHeadersWhenClientExceedsRouteLimit() throws Exception {
    assertThat(get("/api/v1/trust/quote?domain=example.com", "203.0.113.10").statusCode()).isEqualTo(200);
    HttpResponse<String> second = get("/api/v1/trust/quote?domain=example.com", "203.0.113.10");
    assertThat(second.statusCode()).isEqualTo(200);

    HttpResponse<String> limited = get("/api/v1/trust/quote?domain=example.com", "203.0.113.10");
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    assertThat(limited.headers().firstValue("RateLimit-Limit")).contains("2");
    assertThat(limited.headers().firstValue("RateLimit-Remaining")).contains("0");
    assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"");
  }

  @Test
  void tracksSeparateClientIpsIndependently() throws Exception {
    assertThat(get("/api/v1/trust/quote?domain=example.com", "203.0.113.20").statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/trust/quote?domain=example.com", "203.0.113.20").statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/trust/quote?domain=example.com", "203.0.113.20").statusCode()).isEqualTo(429);

    assertThat(get("/api/v1/trust/quote?domain=example.com", "203.0.113.21").statusCode()).isEqualTo(200);
  }

  @Test
  void prefersFlyClientIpWhenForwardedHeadersAreAlsoPresent() throws Exception {
    assertThat(getWithFlyClientIp("/api/v1/trust/quote?domain=example.com", "198.51.100.10", "203.0.113.50").statusCode())
        .isEqualTo(200);
    assertThat(getWithFlyClientIp("/api/v1/trust/quote?domain=example.com", "198.51.100.10", "203.0.113.51").statusCode())
        .isEqualTo(200);

    HttpResponse<String> limited =
        getWithFlyClientIp("/api/v1/trust/quote?domain=example.com", "198.51.100.10", "203.0.113.52");
    assertThat(limited.statusCode()).isEqualTo(429);
  }

  @Test
  void reportEndpointUsesReportSpecificLimit() throws Exception {
    assertThat(get("/api/v1/trust/report?domain=example.com&checks=dns", "203.0.113.30").statusCode()).isEqualTo(200);

    HttpResponse<String> limited = get("/api/v1/trust/report?domain=example.com&checks=dns", "203.0.113.30");
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("RateLimit-Limit")).contains("1");
  }

  @Test
  void healthzIsNotRateLimited() throws Exception {
    assertThat(get("/healthz", "203.0.113.40").statusCode()).isEqualTo(200);
    assertThat(get("/healthz", "203.0.113.40").statusCode()).isEqualTo(200);
    assertThat(get("/healthz", "203.0.113.40").statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> get(String path, String clientIp) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Forwarded-For", clientIp)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getWithFlyClientIp(String path, String flyClientIp, String forwardedFor) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Fly-Client-IP", flyClientIp)
                .header("X-Forwarded-For", forwardedFor)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration
  static class TestOverrides {
    @Bean
    @Primary
    DnsVettingService dnsVettingService() {
      return new DnsVettingService(
          new AddressClassifier(),
          domain -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
    }
  }
}
