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

  private TrustReportService service(FakeSafeHttpClient http, AddressForDomain addressForDomain) {
    return new TrustReportService(
        new DnsVettingService(
            new AddressClassifier(),
            domain -> new InetAddress[] {InetAddress.getByName(addressForDomain.address(domain))}),
        http,
        new ReportSigner(properties, new ObjectMapper()),
        properties);
  }

  @FunctionalInterface
  private interface AddressForDomain {
    String address(String domain);
  }

  private static class FakeSafeHttpClient extends SafeHttpClient {
    private final Map<String, SafeHttpResponse> responses = new LinkedHashMap<>();

    private FakeSafeHttpClient(PaygateReferenceProperties properties) {
      super(properties);
    }

    private void response(String path, int status, Map<String, String> headers, String body) {
      responses.put(path, new SafeHttpResponse(status, headers, body));
    }

    @Override
    public SafeHttpResponse fetch(String domain, List<InetAddress> vettedAddresses, HttpMethod method, String path) {
      return responses.get(path);
    }
  }
}
