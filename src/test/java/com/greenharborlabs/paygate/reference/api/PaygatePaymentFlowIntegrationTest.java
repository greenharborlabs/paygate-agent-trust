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
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Array;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = {PaygateReferenceApplication.class, PaygatePaymentFlowIntegrationTest.TestOverrides.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=paygate-reference-service",
      "paygate.protocols.mpp.challenge-binding-secret=integration-test-secret-at-least-32-bytes-long",
      "reference.report-signing-private-key=MC4CAQAwBQYDK2VwBCIEIKFgoMB34QYC1lTcyWsgFIJcqRY2cNcV2dMHbGGmPvhD",
      "reference.report-signing-public-key=MCowBQYDK2VwAyEAgeVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM=",
      "reference.report-signing-key-id=test-key"
    })
@ActiveProfiles("test")
class PaygatePaymentFlowIntegrationTest {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String REPORT_PATH = "/api/v1/trust/report?domain=example.com&checks=dns";
  @LocalServerPort private int port;

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @Test
  @SuppressWarnings("unchecked")
  void paymentFlow() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var challengeRsp =
          client.send(
              HttpRequest.newBuilder().uri(URI.create(baseUrl() + REPORT_PATH)).GET().build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(challengeRsp.statusCode()).isEqualTo(402);
      List<String> wwwAuthHeaders = challengeRsp.headers().allValues("WWW-Authenticate");
      assertThat(wwwAuthHeaders).anyMatch(h -> h.startsWith("L402"));
      assertThat(wwwAuthHeaders).anyMatch(h -> h.startsWith("Payment"));

      Map<String, Object> challengeBody = MAPPER.readValue(challengeRsp.body(), Map.class);
      String preimageHex = (String) challengeBody.get("test_preimage");
      Map<String, Object> protocols = (Map<String, Object>) challengeBody.get("protocols");
      Map<String, Object> paymentChallenge = (Map<String, Object>) protocols.get("Payment");
      String credentialJson =
          MAPPER.writeValueAsString(
              Map.of(
                  "challenge",
                  paymentChallenge,
                  "source",
                  "test-client",
                  "payload",
                  Map.of("preimage", preimageHex)));
      String credential =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(credentialJson.getBytes(StandardCharsets.UTF_8));

      var paidRsp =
          client.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl() + REPORT_PATH))
                  .header("Authorization", "Payment " + credential)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(paidRsp.statusCode()).isEqualTo(200);
      String receipt = paidRsp.headers().firstValue("Payment-Receipt").orElseThrow();
      Map<String, Object> report = MAPPER.readValue(paidRsp.body(), Map.class);
      assertThat(report).containsKeys("reportDigest", "signature", "checks", "receiptBinding");

      Map<String, Object> catalog =
          MAPPER.readValue(
              client.send(
                      HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/api/v1/catalog")).GET().build(),
                      HttpResponse.BodyHandlers.ofString())
                  .body(),
              Map.class);
      Map<String, Object> signature = (Map<String, Object>) report.get("signature");
      Map<String, Object> receiptBinding = (Map<String, Object>) report.get("receiptBinding");
      assertThat(receiptBinding)
          .containsEntry("receipt", receipt)
          .containsEntry("reportDigest", report.get("reportDigest"))
          .containsEntry("reportSignature", signature.get("value"))
          .containsKey("bindingDigest")
          .containsKey("signature");
      Map<String, Object> catalogSig = (Map<String, Object>) catalog.get("signature");
      Map<String, Object> signable = new LinkedHashMap<>();
      signable.put("domain", report.get("domain"));
      signable.put("checkedAt", report.get("checkedAt"));
      signable.put("checks", report.get("checks"));
      signable.put("verdict", report.get("verdict"));
      assertThat(verify(signable, (String) signature.get("value"), (String) catalogSig.get("publicKey"))).isTrue();

      Map<String, Object> verification = post(client, "/api/v1/trust/verify", report);
      assertThat(verification)
          .containsEntry("valid", true)
          .containsEntry("signatureValid", true)
          .containsEntry("digestMatches", true)
          .containsEntry("receiptBindingValid", true);

      Map<String, Object> tamperedReport = new LinkedHashMap<>(report);
      Map<String, Object> tamperedBinding = new LinkedHashMap<>(receiptBinding);
      tamperedBinding.put("receipt", receipt + "-tampered");
      tamperedReport.put("receiptBinding", tamperedBinding);
      assertThat(post(client, "/api/v1/trust/verify", tamperedReport))
          .containsEntry("valid", false)
          .containsEntry("signatureValid", true)
          .containsEntry("digestMatches", true)
          .containsEntry("receiptBindingValid", false);
    }
  }

  private Map<String, Object> post(HttpClient client, String path, Map<String, Object> body) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readValue(response.body(), Map.class);
  }

  private boolean verify(Map<String, Object> payload, String signatureBase64Url, String publicKeyBase64) throws Exception {
    PublicKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(MAPPER.writeValueAsBytes(canonicalize(payload)));
    return verifier.verify(Base64.getUrlDecoder().decode(signatureBase64Url));
  }

  private Object canonicalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new LinkedHashMap<>();
      map.entrySet().stream()
          .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
          .forEach(entry -> sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue())));
      return sorted;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(this::canonicalize).toList();
    }
    if (value != null && value.getClass().isArray()) {
      List<Object> list = new ArrayList<>();
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        list.add(canonicalize(Array.get(value, i)));
      }
      return list;
    }
    return value;
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
