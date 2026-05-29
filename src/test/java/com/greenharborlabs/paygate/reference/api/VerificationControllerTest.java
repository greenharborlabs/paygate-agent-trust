package com.greenharborlabs.paygate.reference.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.PaygateReferenceApplication;
import com.greenharborlabs.paygate.reference.dns.AddressClassifier;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import com.greenharborlabs.paygate.reference.domain.TrustCheck;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequest;
import com.greenharborlabs.paygate.reference.report.TrustReportService;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = {PaygateReferenceApplication.class, VerificationControllerTest.TestOverrides.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VerificationControllerTest {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  private final TrustReportService trustReportService;

  @Autowired
  VerificationControllerTest(TrustReportService trustReportService) {
    this.trustReportService = trustReportService;
  }

  @Test
  @SuppressWarnings("unchecked")
  void keysEndpointExposesJwksLikeEd25519PublicKey() throws Exception {
    Map<String, Object> body = get("/api/v1/verification/keys");
    var keys = (List<Object>) body.get("keys");
    Map<String, Object> key = (Map<String, Object>) keys.getFirst();

    assertThat(key)
        .containsEntry("kty", "OKP")
        .containsEntry("crv", "Ed25519")
        .containsEntry("kid", "test-key")
        .containsEntry("alg", "EdDSA")
        .containsEntry("use", "sig")
        .containsEntry("x", "geVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM");
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifyEndpointSucceedsForGeneratedReportAndFailsClosedForTampering() throws Exception {
    Map<String, Object> report =
        trustReportService.createReport(new TrustReportRequest("example.com", "example.com", Set.of(TrustCheck.DNS)));

    Map<String, Object> valid = post("/api/v1/trust/verify", report);
    assertThat(valid)
        .containsEntry("valid", true)
        .containsEntry("keyId", "test-key")
        .containsEntry("signatureValid", true)
        .containsEntry("digestMatches", true);
    assertThat(valid.get("reportDigest")).isEqualTo(report.get("reportDigest"));

    Map<String, Object> reorderedVerdict = new LinkedHashMap<>();
    Map<String, Object> originalVerdict = (Map<String, Object>) report.get("verdict");
    reorderedVerdict.put("warnings", originalVerdict.get("warnings"));
    reorderedVerdict.put("status", originalVerdict.get("status"));
    Map<String, Object> reorderedReport = new LinkedHashMap<>();
    reorderedReport.put("signature", report.get("signature"));
    reorderedReport.put("reportDigest", report.get("reportDigest"));
    reorderedReport.put("verdict", reorderedVerdict);
    reorderedReport.put("checks", report.get("checks"));
    reorderedReport.put("checkedAt", report.get("checkedAt"));
    reorderedReport.put("domain", report.get("domain"));
    assertThat(post("/api/v1/trust/verify", reorderedReport)).containsEntry("valid", true);

    Map<String, Object> tamperedChecks = mutableReport(report);
    tamperedChecks.put("checks", Map.of("dns", Map.of("answers", new String[] {"203.0.113.10"})));
    assertThat(post("/api/v1/trust/verify", tamperedChecks))
        .containsEntry("valid", false)
        .containsEntry("signatureValid", false)
        .containsEntry("digestMatches", false);

    Map<String, Object> tamperedVerdict = mutableReport(report);
    tamperedVerdict.put("verdict", Map.of("status", "ok", "warnings", List.of("unsigned-warning")));
    assertThat(post("/api/v1/trust/verify", tamperedVerdict))
        .containsEntry("valid", false)
        .containsEntry("signatureValid", false)
        .containsEntry("digestMatches", false);

    Map<String, Object> tamperedSignature = mutableReport(report);
    Map<String, Object> signature = new LinkedHashMap<>((Map<String, Object>) report.get("signature"));
    signature.put("value", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    tamperedSignature.put("signature", signature);
    assertThat(post("/api/v1/trust/verify", tamperedSignature))
        .containsEntry("valid", false)
        .containsEntry("signatureValid", false)
        .containsEntry("digestMatches", true);

    Map<String, Object> tamperedDigest = mutableReport(report);
    tamperedDigest.put("reportDigest", "sha256:tampered");
    assertThat(post("/api/v1/trust/verify", tamperedDigest))
        .containsEntry("valid", false)
        .containsEntry("signatureValid", true)
        .containsEntry("digestMatches", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifyEndpointReturnsInvalidForUnknownKid() throws Exception {
    Map<String, Object> report =
        trustReportService.createReport(new TrustReportRequest("example.com", "example.com", Set.of(TrustCheck.DNS)));
    Map<String, Object> signature = new LinkedHashMap<>((Map<String, Object>) report.get("signature"));
    signature.put("keyId", "unknown-key");
    report.put("signature", signature);

    assertThat(post("/api/v1/trust/verify", report))
        .containsEntry("valid", false)
        .containsEntry("keyId", "unknown-key")
        .containsEntry("signatureValid", false)
        .containsEntry("digestMatches", true);
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifyEndpointReturnsBadRequestForMissingSignatureMalformedSignatureAndMalformedJson() throws Exception {
    Map<String, Object> report =
        trustReportService.createReport(new TrustReportRequest("example.com", "example.com", Set.of(TrustCheck.DNS)));
    Map<String, Object> malformedSignatureReport = mutableReport(report);
    Map<String, Object> signature = new LinkedHashMap<>((Map<String, Object>) report.get("signature"));
    signature.put("value", "not-base64url");
    malformedSignatureReport.put("signature", signature);
    var malformedSignature =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/trust/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(malformedSignatureReport)))
                .build());
    assertThat(malformedSignature.statusCode()).isEqualTo(400);

    var missingSignature =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/trust/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"domain\":\"example.com\"}"))
                .build());
    assertThat(missingSignature.statusCode()).isEqualTo(400);

    var malformedJson =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/trust/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{"))
                .build());
    assertThat(malformedJson.statusCode()).isEqualTo(400);
  }

  private Map<String, Object> mutableReport(Map<String, Object> report) {
    return new LinkedHashMap<>(report);
  }

  private Map<String, Object> get(String path) throws Exception {
    var response = send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build());
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readValue(response.body(), Map.class);
  }

  private Map<String, Object> post(String path, Map<String, Object> body) throws Exception {
    var response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build());
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readValue(response.body(), Map.class);
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://localhost:" + port;
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
