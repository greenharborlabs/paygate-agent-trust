package com.greenharborlabs.paygate.reference.api;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustCatalogController {
  private final PaygateReferenceProperties properties;

  public TrustCatalogController(PaygateReferenceProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/healthz")
  public Map<String, String> healthz() {
    return Map.of("status", "ok");
  }

  @GetMapping("/api/v1/catalog")
  public Map<String, Object> catalog() {
    return Map.of(
        "service", "paygate-reference-service",
        "checks", List.of("dns", "tls", "http", "redirects", "robots", "security_headers", "content", "risk"),
        "defaultChecks", List.of("dns", "tls", "http", "redirects", "robots", "security_headers", "content", "risk"),
        "pricing",
            Map.of(
                "base", 10,
                "tls", 5,
                "http", 10,
                "redirects", 5,
                "robots", 5,
                "security_headers", 5,
                "content", 5,
                "risk", 5,
                "cap", 50),
        "verification",
            Map.of(
                "keysUrl", "/api/v1/verification/keys",
                "verifyUrl", "/api/v1/trust/verify"),
        "signature", Map.of("algorithm", "Ed25519", "keyId", properties.reportSigningKeyId(), "publicKey", properties.reportSigningPublicKey()));
  }
}
