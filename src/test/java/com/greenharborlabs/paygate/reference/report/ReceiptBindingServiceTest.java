package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReceiptBindingServiceTest {
  private final PaygateReferenceProperties properties =
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
  private final ReportSigner signer = new ReportSigner(properties, new ObjectMapper());
  private final ReceiptBindingService service = new ReceiptBindingService(signer);

  @Test
  @SuppressWarnings("unchecked")
  void bindingTiesReceiptDigestAndReportSignature() {
    Map<String, Object> report = report("sha256:report", "report-signature");

    Map<String, Object> binding = service.bind("receipt-header", "sha256:report", "report-signature");

    assertThat(binding)
        .containsEntry("receipt", "receipt-header")
        .containsEntry("reportDigest", "sha256:report")
        .containsEntry("reportSignature", "report-signature")
        .containsKey("bindingDigest")
        .containsKey("signature");
    assertThat(service.verify(report, binding, "test-key")).isTrue();

    Map<String, Object> tamperedBinding = new LinkedHashMap<>(binding);
    tamperedBinding.put("receipt", "other-receipt");
    assertThat(service.verify(report, tamperedBinding, "test-key")).isFalse();

    Map<String, Object> malformedSignature = new LinkedHashMap<>((Map<String, Object>) binding.get("signature"));
    malformedSignature.put("value", "not-base64url");
    tamperedBinding = new LinkedHashMap<>(binding);
    tamperedBinding.put("signature", malformedSignature);
    assertThat(service.verify(report, tamperedBinding, "test-key")).isFalse();
  }

  private Map<String, Object> report(String digest, String signatureValue) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("reportDigest", digest);
    report.put("signature", Map.of("algorithm", "Ed25519", "keyId", "test-key", "value", signatureValue));
    return report;
  }
}
