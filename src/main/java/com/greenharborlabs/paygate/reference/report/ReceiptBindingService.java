package com.greenharborlabs.paygate.reference.report;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReceiptBindingService {
  private final ReportSigner reportSigner;

  public ReceiptBindingService(ReportSigner reportSigner) {
    this.reportSigner = reportSigner;
  }

  public Map<String, Object> bind(String receipt, String reportDigest, String reportSignature) {
    Map<String, Object> payload = bindingPayload(receipt, reportDigest, reportSignature);
    ReportSigner.Signed signed = reportSigner.sign(payload);

    Map<String, Object> binding = new LinkedHashMap<>(payload);
    binding.put("bindingDigest", signed.digest());
    binding.put(
        "signature",
        Map.of("algorithm", signed.algorithm(), "keyId", signed.keyId(), "value", signed.signature()));
    return binding;
  }

  public boolean verify(Map<String, Object> report, Map<?, ?> binding, String expectedKeyId) {
    try {
      Object bindingSignatureValue = binding.get("signature");
      if (!(bindingSignatureValue instanceof Map<?, ?> bindingSignature)) return false;
      if (!expectedKeyId.equals(bindingSignature.get("keyId"))) return false;
      Object valueValue = bindingSignature.get("value");
      if (!(valueValue instanceof String value) || value.isBlank()) return false;

      Object receiptValue = binding.get("receipt");
      Object reportDigestValue = binding.get("reportDigest");
      Object reportSignatureValue = binding.get("reportSignature");
      if (!(receiptValue instanceof String receipt)
          || !(reportDigestValue instanceof String reportDigest)
          || !(reportSignatureValue instanceof String reportSignature)) {
        return false;
      }
      if (!reportDigest.equals(report.get("reportDigest"))) return false;
      Object reportSignatureObject = report.get("signature");
      if (!(reportSignatureObject instanceof Map<?, ?> signature)) return false;
      if (!reportSignature.equals(signature.get("value"))) return false;

      Map<String, Object> payload = bindingPayload(receipt, reportDigest, reportSignature);
      if (!reportSigner.digest(payload).equals(binding.get("bindingDigest"))) return false;
      return reportSigner.verify(payload, value);
    } catch (ApiProblem problem) {
      return false;
    }
  }

  private Map<String, Object> bindingPayload(String receipt, String reportDigest, String reportSignature) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("receipt", receipt);
    payload.put("reportDigest", reportDigest);
    payload.put("reportSignature", reportSignature);
    return payload;
  }
}
