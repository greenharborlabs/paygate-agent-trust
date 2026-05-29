package com.greenharborlabs.paygate.reference.report;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.lang.reflect.Array;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
      byte[] payload = canonicalPayloadBytes(canonicalPayload);
      String digest = digest(payload);
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

  public String digest(Map<String, Object> canonicalPayload) {
    try {
      return digest(canonicalPayloadBytes(canonicalPayload));
    } catch (Exception ex) {
      throw new ApiProblem("REPORT_DIGEST_FAILED", HttpStatus.BAD_REQUEST, false, "Report digest failed.");
    }
  }

  public boolean verify(Map<String, Object> canonicalPayload, String signatureBase64Url) {
    try {
      byte[] payload = canonicalPayloadBytes(canonicalPayload);
      byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureBase64Url);
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(loadPublicKey(properties.reportSigningPublicKey()));
      verifier.update(payload);
      return verifier.verify(signatureBytes);
    } catch (IllegalArgumentException ex) {
      throw new ApiProblem("INVALID_SIGNATURE", HttpStatus.BAD_REQUEST, false, "Malformed signature.");
    } catch (SignatureException ex) {
      throw new ApiProblem("INVALID_SIGNATURE", HttpStatus.BAD_REQUEST, false, "Malformed signature.");
    } catch (Exception ex) {
      throw new ApiProblem("REPORT_VERIFICATION_FAILED", HttpStatus.SERVICE_UNAVAILABLE, true, "Report verification failed.");
    }
  }

  public String rawPublicKeyBase64Url() {
    byte[] publicKey = Base64.getDecoder().decode(properties.reportSigningPublicKey());
    byte[] raw = new byte[32];
    System.arraycopy(publicKey, publicKey.length - raw.length, raw, 0, raw.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private PrivateKey loadPrivateKey(String b64Pkcs8) throws Exception {
    byte[] bytes = Base64.getDecoder().decode(b64Pkcs8);
    return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
  }

  private PublicKey loadPublicKey(String b64X509) throws Exception {
    byte[] bytes = Base64.getDecoder().decode(b64X509);
    return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
  }

  private String digest(byte[] payload) throws Exception {
    return "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload));
  }

  private byte[] canonicalPayloadBytes(Map<String, Object> canonicalPayload) throws Exception {
    return objectMapper.writeValueAsBytes(canonicalize(canonicalPayload));
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
