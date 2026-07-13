package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import com.greenharborlabs.paygate.reference.dns.AddressClassifier;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import com.greenharborlabs.paygate.reference.domain.TrustCheck;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequest;
import com.greenharborlabs.paygate.reference.http.SafeHttpClient;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

class TrustReportServiceTest {
  private final PaygateReferenceProperties properties =
      new PaygateReferenceProperties(
          1, 2, 3, 6, 65536, 8192, 32, 15, 16,
          "MC4CAQAwBQYDK2VwBCIEIKFgoMB34QYC1lTcyWsgFIJcqRY2cNcV2dMHbGGmPvhD",
          "MCowBQYDK2VwAyEAgeVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM=",
          "test-key");

  @Test
  @SuppressWarnings("unchecked")
  void robotsMissingIsReportedAsMissingWithoutFailure() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient(properties);
    http.response("/robots.txt", 404, Map.of(), "");
    http.response("/ai.txt", 404, Map.of(), "");
    TrustReportService service = service(http, domain -> "93.184.216.34");

    Map<String, Object> report = service.createReport(new TrustReportRequest("example.com", "example.com", Set.of(TrustCheck.ROBOTS)));
    Map<String, Object> checks = (Map<String, Object>) report.get("checks");

    assertThat((Map<String, Object>) checks.get("robots"))
        .containsEntry("status", "missing")
        .containsEntry("robotsStatus", 404)
        .containsEntry("aiStatus", 404);
    assertThat((Map<String, Object>) report.get("verdict")).containsEntry("status", "ok");
  }

  @Test
  @SuppressWarnings("unchecked")
  void redirectUnsafeTargetPropagatesVerdictWarning() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient(properties);
    http.response("/", 302, Map.of("Location", "https://internal.example/"), "");
    TrustReportService service =
        service(http, domain -> domain.equals("internal.example") ? "10.0.0.1" : "93.184.216.34");

    Map<String, Object> report = service.createReport(new TrustReportRequest("example.com", "example.com", Set.of(TrustCheck.REDIRECTS)));
    Map<String, Object> verdict = (Map<String, Object>) report.get("verdict");

    assertThat(verdict).containsEntry("status", "warn");
    assertThat((List<String>) verdict.get("warnings")).contains("redirects:redirect-unsafe-target");
  }

  @Test
  @SuppressWarnings("unchecked")
  void securityHeadersAndContentUseCapturedRootResponseAndPropagateWarnings() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient(properties);
    http.response(
        "/",
        200,
        Map.of(
            "Content-Type", "text/html",
            "Strict-Transport-Security", "max-age=120",
            "Content-Security-Policy", "script-src * 'unsafe-inline'",
            "X-Robots-Tag", "noindex"),
        """
        <html><body>
          <form action="/login"><input type="password"></form>
          Subscribe to continue reading.
        </body></html>
        """);
    TrustReportService service = service(http, domain -> "93.184.216.34");

    Map<String, Object> report =
        service.createReport(
            new TrustReportRequest(
                "example.com",
                "example.com",
                Set.of(TrustCheck.HTTP, TrustCheck.SECURITY_HEADERS, TrustCheck.CONTENT)));
    Map<String, Object> checks = (Map<String, Object>) report.get("checks");
    Map<String, Object> securityHeaders = (Map<String, Object>) checks.get("security_headers");
    Map<String, Object> content = (Map<String, Object>) checks.get("content");
    Map<String, Object> verdict = (Map<String, Object>) report.get("verdict");

    assertThat(http.fetchCount("/")).isEqualTo(1);
    assertThat(securityHeaders).containsEntry("status", "warn");
    assertThat(content).containsEntry("analyzed", true).containsEntry("kind", "html");
    assertThat((List<String>) verdict.get("warnings"))
        .contains(
            "security_headers:hsts-weak",
            "security_headers:csp-weak",
            "content:login-form-detected",
            "content:paywall-detected",
            "content:noindex-detected");
  }

  @Test
  @SuppressWarnings("unchecked")
  void selectedRiskCheckScoresPopulatedChecksInsteadOfReturningDeferredPlaceholder() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient(properties);
    http.response(
        "/",
        200,
        Map.of("Content-Type", "text/html"),
        "<html><body><form action=\"/login\"><input type=\"password\"></form></body></html>");
    TrustReportService service = service(http, domain -> "93.184.216.34");

    Map<String, Object> report =
        service.createReport(
            new TrustReportRequest(
                "example.com",
                "example.com",
                Set.of(TrustCheck.HTTP, TrustCheck.SECURITY_HEADERS, TrustCheck.CONTENT, TrustCheck.RISK)));
    Map<String, Object> checks = (Map<String, Object>) report.get("checks");
    Map<String, Object> risk = (Map<String, Object>) checks.get("risk");
    Map<String, Object> topLevelRisk = (Map<String, Object>) report.get("risk");
    List<Map<String, Object>> explanations = (List<Map<String, Object>>) risk.get("explanations");
    List<Map<String, Object>> notEvaluated = (List<Map<String, Object>>) risk.get("notEvaluated");

    assertThat(topLevelRisk).isEqualTo(risk);
    assertThat(risk).containsKeys("score", "level", "explanations", "notEvaluated");
    assertThat(risk).doesNotContainEntry("status", "not_evaluated");
    assertThat((Integer) risk.get("score")).isGreaterThan(0);
    assertThat(explanations.stream().map(explanation -> String.valueOf(explanation.get("path"))).toList())
        .contains("checks.security_headers.findings.hsts.state", "checks.content.login.detected");
    assertThat(notEvaluated.stream().map(entry -> String.valueOf(entry.get("path"))).toList())
        .contains(
            "checks.tls",
            "checks.redirects",
            "checks.robots",
            "providers.phishing_malware",
            "providers.reputation",
            "providers.domain_registration");
    assertValidSignature(report);
  }

  @Test
  @SuppressWarnings("unchecked")
  void omittedRiskCheckOmitsBothRiskLocationsAndKeepsSignatureValid() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient(properties);
    TrustReportService service = service(http, domain -> "93.184.216.34");

    Map<String, Object> report =
        service.createReport(
            new TrustReportRequest("example.com", "example.com", Set.of(TrustCheck.DNS)));
    Map<String, Object> checks = (Map<String, Object>) report.get("checks");

    assertThat(checks).doesNotContainKey("risk");
    assertThat(report).doesNotContainKey("risk");
    assertValidSignature(report);
  }

  private void assertValidSignature(Map<String, Object> report) {
    ReportSigner signer = new ReportSigner(properties, new ObjectMapper());
    ReportVerificationService verificationService =
        new ReportVerificationService(signer, new ReceiptBindingService(signer), properties);
    assertThat(verificationService.verify(report))
        .containsEntry("valid", true)
        .containsEntry("signatureValid", true)
        .containsEntry("digestMatches", true);
  }

  private TrustReportService service(FakeSafeHttpClient http, AddressForDomain addressForDomain) {
    ReportSigner signer = new ReportSigner(properties, new ObjectMapper());
    return new TrustReportService(
        new DnsVettingService(
            new AddressClassifier(),
            domain -> new InetAddress[] {InetAddress.getByName(addressForDomain.address(domain))}),
        http,
        signer,
        new ReceiptBindingService(signer),
        properties);
  }

  @FunctionalInterface
  private interface AddressForDomain {
    String address(String domain);
  }

  private static class FakeSafeHttpClient extends SafeHttpClient {
    private final Map<String, SafeHttpResponse> responses = new LinkedHashMap<>();
    private final Map<String, Integer> fetchCounts = new LinkedHashMap<>();

    private FakeSafeHttpClient(PaygateReferenceProperties properties) {
      super(properties);
    }

    private void response(String path, int status, Map<String, String> headers, String body) {
      responses.put(path, new SafeHttpResponse(status, headers, body));
    }

    @Override
    public SafeHttpResponse fetch(String domain, List<InetAddress> vettedAddresses, HttpMethod method, String path) {
      fetchCounts.merge(path, 1, Integer::sum);
      return responses.get(path);
    }

    private int fetchCount(String path) {
      return fetchCounts.getOrDefault(path, 0);
    }
  }
}
