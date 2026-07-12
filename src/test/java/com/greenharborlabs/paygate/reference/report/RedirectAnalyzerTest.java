package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import com.greenharborlabs.paygate.reference.dns.AddressClassifier;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import com.greenharborlabs.paygate.reference.http.SafeHttpClient;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class RedirectAnalyzerTest {
  @Test
  @SuppressWarnings("unchecked")
  void reportsNoRedirectTerminalStatus() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient();
    http.response("example.com", "/", 200, Map.of());
    RedirectAnalyzer analyzer = new RedirectAnalyzer(dns("93.184.216.34"), http);

    Map<String, Object> result = analyzer.analyze("example.com", List.of(InetAddress.getByName("93.184.216.34")));

    assertThat(result).containsEntry("status", "ok").containsEntry("terminalStatus", 200);
    assertThat((List<Map<String, Object>>) result.get("hops")).singleElement().satisfies(hop -> assertThat(hop).containsEntry("url", "https://example.com/"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void followsCrossHostRedirectAfterVettingNewHost() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient();
    http.response("example.com", "/", 302, Map.of("Location", "https://www.example.com/final"));
    http.response("www.example.com", "/final", 204, Map.of());
    RedirectAnalyzer analyzer = new RedirectAnalyzer(dns("93.184.216.34"), http);

    Map<String, Object> result = analyzer.analyze("example.com", List.of(InetAddress.getByName("93.184.216.34")));

    assertThat(result).containsEntry("terminalStatus", 204);
    assertThat((List<Map<String, Object>>) result.get("hops")).hasSize(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void blocksUnsafeRedirectDestinationAsStructuredWarning() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient();
    http.response("example.com", "/", 302, Map.of("Location", "https://internal.example/"));
    RedirectAnalyzer analyzer =
        new RedirectAnalyzer(dns("10.0.0.1"), http);

    Map<String, Object> result = analyzer.analyze("example.com", List.of(InetAddress.getByName("93.184.216.34")));

    assertThat(result).containsEntry("status", "warn");
    assertThat((List<String>) result.get("warnings")).contains("redirect-unsafe-target");
    assertThat((List<Map<String, Object>>) result.get("hops")).last().satisfies(hop -> assertThat(hop).containsEntry("status", "blocked"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void representsRedirectLoopsAsWarnings() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient();
    http.response("example.com", "/", 302, Map.of("Location", "/"));
    RedirectAnalyzer analyzer = new RedirectAnalyzer(dns("93.184.216.34"), http);

    Map<String, Object> result = analyzer.analyze("example.com", List.of(InetAddress.getByName("93.184.216.34")));

    assertThat((List<String>) result.get("warnings")).contains("redirect-loop");
  }

  @Test
  @SuppressWarnings("unchecked")
  void representsMaxHopExhaustionAsWarning() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient();
    http.response("example.com", "/", 302, Map.of("Location", "/1"));
    http.response("example.com", "/1", 302, Map.of("Location", "/2"));
    http.response("example.com", "/2", 302, Map.of("Location", "/3"));
    http.response("example.com", "/3", 302, Map.of("Location", "/4"));
    http.response("example.com", "/4", 302, Map.of("Location", "/5"));
    RedirectAnalyzer analyzer = new RedirectAnalyzer(dns("93.184.216.34"), http);

    Map<String, Object> result = analyzer.analyze("example.com", List.of(InetAddress.getByName("93.184.216.34")));

    assertThat(result).containsEntry("status", "warn");
    assertThat((List<String>) result.get("warnings")).contains("redirect-max-hops");
    assertThat((List<Map<String, Object>>) result.get("hops")).hasSize(5);
  }

  @Test
  @SuppressWarnings("unchecked")
  void representsMalformedRedirectLocationAsBoundedWarning() throws Exception {
    FakeSafeHttpClient http = new FakeSafeHttpClient();
    http.response("example.com", "/", 302, Map.of("Location", "https://[invalid"));
    RedirectAnalyzer analyzer = new RedirectAnalyzer(dns("93.184.216.34"), http);

    Map<String, Object> result =
        analyzer.analyze("example.com", List.of(InetAddress.getByName("93.184.216.34")));

    assertThat(result).containsEntry("status", "warn").containsEntry("terminalStatus", 302);
    assertThat((List<String>) result.get("warnings")).containsExactly("redirect-malformed-location");
    assertThat((List<Map<String, Object>>) result.get("hops")).singleElement();
  }

  private DnsVettingService dns(String address) {
    return new DnsVettingService(new AddressClassifier(), domain -> new InetAddress[] {InetAddress.getByName(address)});
  }

  private static class FakeSafeHttpClient extends SafeHttpClient {
    private final Map<String, SafeHttpResponse> responses = new LinkedHashMap<>();

    private FakeSafeHttpClient() {
      super(
          new PaygateReferenceProperties(
              1, 2, 3, 6, 65536, 8192, 32, 15, 16,
              "MC4CAQAwBQYDK2VwBCIEIKFgoMB34QYC1lTcyWsgFIJcqRY2cNcV2dMHbGGmPvhD",
              "MCowBQYDK2VwAyEAgeVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM=",
              "test-key"));
    }

    private void response(String domain, String path, int status, Map<String, String> headers) {
      responses.put(domain + path, new SafeHttpResponse(status, headers, ""));
    }

    @Override
    public SafeHttpResponse fetch(String domain, List<InetAddress> vettedAddresses, HttpMethod method, String path) {
      return responses.get(domain + path);
    }
  }
}
