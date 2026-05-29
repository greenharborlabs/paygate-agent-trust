package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
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
  }

  @Test
  void tamperedPayloadFailsVerification() throws Exception {
    var signer = new ReportSigner(PROPS, new ObjectMapper());
    var payload = Map.of("domain", "example.com", "checks", Map.of("dns", Map.of("answers", "ok")));
    var signed = signer.sign(payload);
    var tampered = Map.of("domain", "evil.com", "checks", Map.of("dns", Map.of("answers", "ok")));
    assertThat(verify(tampered, signed.signature(), signed.publicKey())).isFalse();
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
    verifier.update(new ObjectMapper().writeValueAsBytes(payload));
    return verifier.verify(signature);
  }
}
