package com.greenharborlabs.paygate.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import com.greenharborlabs.paygate.reference.dns.AddressClassifier;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import com.greenharborlabs.paygate.reference.domain.DomainValidator;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequestParser;
import com.greenharborlabs.paygate.reference.http.SafeHttpClient;
import com.greenharborlabs.paygate.reference.pricing.TrustReportPriceCalculator;
import com.greenharborlabs.paygate.reference.report.ReceiptBindingService;
import com.greenharborlabs.paygate.reference.report.ReportSigner;
import com.greenharborlabs.paygate.reference.report.TrustReportService;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class TrustReportControllerTest {
  private final TrustReportController controller = buildController();

  @Test
  void quoteWorks() {
    Map<String, Object> quote = controller.quote("example.com", "dns,tls,http");
    assertThat(quote.get("priceSats")).isEqualTo(25L);
  }

  @Test
  void invalidDomainYieldsProblem() {
    assertThatThrownBy(() -> controller.quote("http://example.com", "dns"))
        .isInstanceOf(ApiProblem.class)
        .satisfies(
            ex -> {
              ApiProblem problem = (ApiProblem) ex;
              assertThat(problem.code()).isEqualTo("INVALID_DOMAIN");
              assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
  }

  @Test
  void unsupportedCheckYieldsProblem() {
    assertThatThrownBy(() -> controller.quote("example.com", "dns,unknown"))
        .isInstanceOf(ApiProblem.class)
        .satisfies(
            ex -> {
              ApiProblem problem = (ApiProblem) ex;
              assertThat(problem.code()).isEqualTo("UNSUPPORTED_CHECK");
              assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void reportIncludesDeferredEntriesForFutureChecks() {
    Map<String, Object> report =
        controller.report("example.com", "redirects,security_headers,content,risk", new MockHttpServletResponse());
    Map<String, Object> checks = (Map<String, Object>) report.get("checks");

    assertThat(checks.keySet()).containsExactly("redirects", "security_headers", "content", "risk");
    assertThat((Map<String, Object>) checks.get("redirects"))
        .containsEntry("status", "ok")
        .containsEntry("terminalStatus", 200);
    Map<String, Object> securityHeaders = (Map<String, Object>) checks.get("security_headers");
    Map<String, Object> securityFindings = (Map<String, Object>) securityHeaders.get("findings");
    assertThat(securityHeaders).containsEntry("status", "warn");
    assertThat((Map<String, Object>) securityFindings.get("hsts")).containsEntry("state", "missing");
    assertThat((Map<String, Object>) securityFindings.get("csp")).containsEntry("state", "missing");
    assertThat((Map<String, Object>) securityFindings.get("frame_protection")).containsEntry("state", "missing");
    assertThat((Map<String, Object>) securityFindings.get("referrer_policy")).containsEntry("state", "missing");
    assertThat((Map<String, Object>) securityFindings.get("permissions_policy")).containsEntry("state", "missing");
    assertThat((Map<String, Object>) securityFindings.get("x_content_type_options")).containsEntry("state", "missing");
    assertThat((List<String>) securityHeaders.get("warnings"))
        .contains(
            "hsts-missing",
            "csp-missing",
            "frame-protection-missing",
            "referrer-policy-missing",
            "permissions-policy-missing",
            "x-content-type-options-missing");

    Map<String, Object> content = (Map<String, Object>) checks.get("content");
    assertThat(content)
        .containsEntry("analyzed", false)
        .containsEntry("contentType", "unknown")
        .containsEntry("reason", "unsupported_content")
        .containsEntry("warnings", List.of("unsupported-content"));

    Map<String, Object> risk = (Map<String, Object>) checks.get("risk");
    assertThat(report.get("risk")).isEqualTo(risk);
    List<Map<String, Object>> explanations = (List<Map<String, Object>>) risk.get("explanations");
    List<Map<String, Object>> notEvaluated = (List<Map<String, Object>>) risk.get("notEvaluated");
    assertThat(risk)
        .containsEntry("status", "warn")
        .containsEntry("score", 80)
        .containsEntry("level", "critical");
    assertThat(explanations.stream().map(explanation -> String.valueOf(explanation.get("path"))).toList())
        .contains(
            "checks.security_headers.findings.hsts.state",
            "checks.security_headers.findings.csp.state",
            "checks.security_headers.findings.frame_protection.state",
            "checks.security_headers.findings.referrer_policy.state",
            "checks.security_headers.findings.permissions_policy.state",
            "checks.security_headers.findings.x_content_type_options.state",
            "checks.content.status");
    assertThat(notEvaluated.stream().map(entry -> String.valueOf(entry.get("path"))).toList())
        .containsExactly(
            "checks.dns",
            "checks.tls",
            "checks.http",
            "checks.robots",
            "providers.phishing_malware",
            "providers.reputation",
            "providers.domain_registration");
    assertThat((Map<String, Object>) report.get("verdict"))
        .containsEntry("status", "warn")
        .containsEntry(
            "warnings",
            List.of(
                "security_headers:hsts-missing",
                "security_headers:csp-missing",
                "security_headers:frame-protection-missing",
                "security_headers:referrer-policy-missing",
                "security_headers:permissions-policy-missing",
                "security_headers:x-content-type-options-missing",
                "content:unsupported-content"));
  }

  private static TrustReportController buildController() {
    var parser = new TrustReportRequestParser(new DomainValidator());
    var calc = new TrustReportPriceCalculator();
    var props =
        new PaygateReferenceProperties(
            1,
            2,
            3,
            6,
            65536,
            8192,
            32,
            15,
            16,
            "MC4CAQAwBQYDK2VwBCIEIKFgoMB34QYC1lTcyWsgFIJcqRY2cNcV2dMHbGGmPvhD",
            "MCowBQYDK2VwAyEAgeVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM=",
            "test-key");
    var signer = new ReportSigner(props, new ObjectMapper());
    var receiptBindingService = new ReceiptBindingService(signer);
    var service =
        new TrustReportService(
            new DnsVettingService(new AddressClassifier(), domain -> new java.net.InetAddress[] {java.net.InetAddress.getByName("93.184.216.34")}),
            new StubSafeHttpClient(props),
            signer,
            receiptBindingService,
            props);
    return new TrustReportController(parser, calc, service);
  }

  private static class StubSafeHttpClient extends SafeHttpClient {
    private StubSafeHttpClient(PaygateReferenceProperties properties) {
      super(properties);
    }

    @Override
    public SafeHttpResponse fetch(String domain, List<InetAddress> vettedAddresses, HttpMethod method, String path) {
      return new SafeHttpResponse(200, Map.of(), "");
    }
  }
}
