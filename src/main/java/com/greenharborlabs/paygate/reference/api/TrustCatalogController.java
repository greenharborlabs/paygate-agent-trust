package com.greenharborlabs.paygate.reference.api;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
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
        "checks", new String[] {"dns", "tls", "http", "robots"},
        "pricing", Map.of("base", 10, "tls", 5, "http", 10, "robots", 5, "cap", 50),
        "signature", Map.of("algorithm", "Ed25519", "keyId", properties.reportSigningKeyId(), "publicKey", properties.reportSigningPublicKey()));
  }
}
