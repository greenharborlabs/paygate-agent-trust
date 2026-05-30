package com.greenharborlabs.paygate.reference.report;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReportVerificationService {
  private final ReportSigner reportSigner;
  private final ReceiptBindingService receiptBindingService;
  private final PaygateReferenceProperties properties;

  public ReportVerificationService(
      ReportSigner reportSigner, ReceiptBindingService receiptBindingService, PaygateReferenceProperties properties) {
    this.reportSigner = reportSigner;
    this.receiptBindingService = receiptBindingService;
    this.properties = properties;
  }

  public Map<String, Object> verify(Map<String, Object> report) {
    Object signatureValue = report.get("signature");
    if (!(signatureValue instanceof Map<?, ?> signature)) {
      throw new ApiProblem("MISSING_SIGNATURE", HttpStatus.BAD_REQUEST, false, "Report signature is required.");
    }
    Object keyIdValue = signature.get("keyId");
    Object valueValue = signature.get("value");
    if (!(keyIdValue instanceof String keyId) || keyId.isBlank() || !(valueValue instanceof String value) || value.isBlank()) {
      throw new ApiProblem("INVALID_SIGNATURE", HttpStatus.BAD_REQUEST, false, "Malformed signature.");
    }

    String reportDigest = report.get("reportDigest") instanceof String digest ? digest : null;
    Map<String, Object> canonicalPayload = canonicalPayload(report);
    String calculatedDigest = reportSigner.digest(canonicalPayload);
    boolean digestMatches = calculatedDigest.equals(reportDigest);
    boolean signatureValid = properties.reportSigningKeyId().equals(keyId) && reportSigner.verify(canonicalPayload, value);

    Map<String, Object> result = new LinkedHashMap<>();
    boolean receiptBindingPresent = report.containsKey("receiptBinding");
    boolean receiptBindingValid = !receiptBindingPresent || receiptBindingValid(report, keyId);
    result.put("valid", signatureValid && digestMatches && receiptBindingValid);
    result.put("reportDigest", reportDigest);
    result.put("keyId", keyId);
    result.put("signatureValid", signatureValid);
    result.put("digestMatches", digestMatches);
    if (receiptBindingPresent) {
      result.put("receiptBindingValid", receiptBindingValid);
    }
    return result;
  }

  public Map<String, Object> keys() {
    return Map.of(
        "keys",
        new Object[] {
          Map.of(
              "kty", "OKP",
              "crv", "Ed25519",
              "kid", properties.reportSigningKeyId(),
              "alg", "EdDSA",
              "use", "sig",
              "x", reportSigner.rawPublicKeyBase64Url())
        });
  }

  private Map<String, Object> canonicalPayload(Map<String, Object> report) {
    Map<String, Object> canonicalPayload = new LinkedHashMap<>();
    canonicalPayload.put("domain", report.get("domain"));
    canonicalPayload.put("checkedAt", report.get("checkedAt"));
    canonicalPayload.put("checks", report.get("checks"));
    canonicalPayload.put("verdict", report.get("verdict"));
    return canonicalPayload;
  }

  private boolean receiptBindingValid(Map<String, Object> report, String keyId) {
    Object binding = report.get("receiptBinding");
    if (!(binding instanceof Map<?, ?> bindingMap)) return false;
    return receiptBindingService.verify(report, bindingMap, keyId);
  }
}
