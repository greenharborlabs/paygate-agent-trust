package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReportSignerTest {
  private static final PaygateReferenceProperties PROPS =
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

  @Test
  void signsPayloadAndVerifies() throws Exception {
    var signer = new ReportSigner(PROPS, new ObjectMapper());
    var payload = Map.of("domain", "example.com", "checks", Map.of("dns", Map.of("answers", "ok")));
    var signed = signer.sign(payload);
    assertThat(signed.algorithm()).isEqualTo("Ed25519");
    assertThat(signed.signature()).isNotBlank();
    assertThat(verify(payload, signed.signature(), signed.publicKey())).isTrue();
    assertThat(signer.verify(payload, signed.signature())).isTrue();
    assertThat(signer.digest(payload)).isEqualTo(signed.digest());
  }

  @Test
  void tamperedPayloadFailsVerification() throws Exception {
    var signer = new ReportSigner(PROPS, new ObjectMapper());
    var payload = Map.of("domain", "example.com", "checks", Map.of("dns", Map.of("answers", "ok")));
    var signed = signer.sign(payload);
    var tampered = Map.of("domain", "evil.com", "checks", Map.of("dns", Map.of("answers", "ok")));
    assertThat(verify(tampered, signed.signature(), signed.publicKey())).isFalse();
    assertThat(signer.verify(tampered, signed.signature())).isFalse();
  }

  @Test
  void verificationIgnoresObjectKeyOrder() throws Exception {
    var signer = new ReportSigner(PROPS, new ObjectMapper());
    Map<String, Object> dns = new LinkedHashMap<>();
    dns.put("answers", List.of("93.184.216.34"));
    dns.put("status", "ok");
    Map<String, Object> checks = new LinkedHashMap<>();
    checks.put("dns", dns);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("domain", "example.com");
    payload.put("checkedAt", "2026-05-29T20:00:00Z");
    payload.put("checks", checks);
    payload.put("verdict", Map.of("status", "ok", "warnings", List.of()));

    var signed = signer.sign(payload);

    Map<String, Object> reorderedDns = new LinkedHashMap<>();
    reorderedDns.put("status", "ok");
    reorderedDns.put("answers", List.of("93.184.216.34"));
    Map<String, Object> reorderedChecks = new LinkedHashMap<>();
    reorderedChecks.put("dns", reorderedDns);
    Map<String, Object> reordered = new LinkedHashMap<>();
    reordered.put("verdict", Map.of("warnings", List.of(), "status", "ok"));
    reordered.put("checks", reorderedChecks);
    reordered.put("checkedAt", "2026-05-29T20:00:00Z");
    reordered.put("domain", "example.com");

    assertThat(signer.digest(reordered)).isEqualTo(signed.digest());
    assertThat(signer.verify(reordered, signed.signature())).isTrue();
    assertThat(verify(reordered, signed.signature(), signed.publicKey())).isTrue();
  }

  @Test
  void exposesRawPublicKeyAsBase64Url() {
    var signer = new ReportSigner(PROPS, new ObjectMapper());
    assertThat(signer.rawPublicKeyBase64Url()).isEqualTo("geVa2jClnW2JYB9MQVL1J0zsIrzv7QMneV5avr19sHM");
  }

  @Test
  void missingKeyMapsToReportSigningFailed() {
    var badProps =
        new PaygateReferenceProperties(
            1, 2, 3, 6, 65536, 8192, 32, 15, 16, "not-base64", PROPS.reportSigningPublicKey(), PROPS.reportSigningKeyId());
    var signer = new ReportSigner(badProps, new ObjectMapper());
    assertThatThrownBy(() -> signer.sign(Map.of("domain", "example.com")))
        .isInstanceOf(ApiProblem.class)
        .satisfies(ex -> assertThat(((ApiProblem) ex).code()).isEqualTo("REPORT_SIGNING_FAILED"));
  }

  private boolean verify(Map<String, Object> payload, String signatureBase64Url, String publicKeyBase64) throws Exception {
    byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
    PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
    byte[] signature = Base64.getUrlDecoder().decode(signatureBase64Url);
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(new ObjectMapper().writeValueAsBytes(canonicalize(payload)));
    return verifier.verify(signature);
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
}
