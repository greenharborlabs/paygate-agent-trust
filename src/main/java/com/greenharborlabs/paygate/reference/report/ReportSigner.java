package com.greenharborlabs.paygate.reference.report;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReportSigner {
  public record Signed(String digest, String algorithm, String keyId, String signature, String publicKey) {}

  private final PaygateReferenceProperties properties;
  private final ObjectMapper objectMapper;

  public ReportSigner(PaygateReferenceProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public Signed sign(Map<String, Object> canonicalPayload) {
    try {
      byte[] payload = objectMapper.writeValueAsBytes(canonicalPayload);
      String digest = "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload));
      PrivateKey key = loadPrivateKey(properties.reportSigningPrivateKey());
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(key);
      signature.update(payload);
      String value = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
      return new Signed(digest, "Ed25519", properties.reportSigningKeyId(), value, properties.reportSigningPublicKey());
    } catch (Exception ex) {
      throw new ApiProblem("REPORT_SIGNING_FAILED", HttpStatus.SERVICE_UNAVAILABLE, true, "Report signing failed.");
    }
  }

  private PrivateKey loadPrivateKey(String b64Pkcs8) throws Exception {
    byte[] bytes = Base64.getDecoder().decode(b64Pkcs8);
    return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
  }
}
