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
import com.greenharborlabs.paygate.reference.report.ReportSigner;
import com.greenharborlabs.paygate.reference.report.TrustReportService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
    Map<String, Object> report = controller.report("example.com", "redirects,security_headers,content,risk");
    Map<String, Object> checks = (Map<String, Object>) report.get("checks");

    assertThat(checks.keySet()).containsExactly("redirects", "security_headers", "content", "risk");
    assertThat(checks.values())
        .allSatisfy(
            check -> {
              Map<String, Object> value = (Map<String, Object>) check;
              assertThat(value)
                  .containsEntry("status", "not_evaluated")
                  .containsEntry("reason", "deferred_to_later_wave");
            });
    assertThat((Map<String, Object>) report.get("verdict"))
        .containsEntry("status", "ok")
        .containsEntry("warnings", List.of());
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
    var service =
        new TrustReportService(
            new DnsVettingService(new AddressClassifier(), domain -> new java.net.InetAddress[] {java.net.InetAddress.getByName("93.184.216.34")}),
            new SafeHttpClient(props),
            signer,
            props);
    return new TrustReportController(parser, calc, service);
  }
}
