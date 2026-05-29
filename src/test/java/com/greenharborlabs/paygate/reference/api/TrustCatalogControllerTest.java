package com.greenharborlabs.paygate.reference.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrustCatalogControllerTest {
  private final TrustCatalogController controller =
      new TrustCatalogController(
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
              "private-key",
              "public-key",
              "test-key"));

  @Test
  @SuppressWarnings("unchecked")
  void catalogPublishesExpandedChecksPricingDefaultsAndVerificationUrls() {
    Map<String, Object> catalog = controller.catalog();
    List<String> expectedChecks =
        List.of("dns", "tls", "http", "redirects", "robots", "security_headers", "content", "risk");

    assertThat(catalog.get("checks")).isEqualTo(expectedChecks);
    assertThat(catalog.get("defaultChecks")).isEqualTo(expectedChecks);
    assertThat((Map<String, Object>) catalog.get("pricing"))
        .containsEntry("base", 10)
        .containsEntry("tls", 5)
        .containsEntry("http", 10)
        .containsEntry("redirects", 5)
        .containsEntry("robots", 5)
        .containsEntry("security_headers", 5)
        .containsEntry("content", 5)
        .containsEntry("risk", 5)
        .containsEntry("cap", 50);
    assertThat((Map<String, Object>) catalog.get("verification"))
        .containsEntry("keysUrl", "/api/v1/verification/keys")
        .containsEntry("verifyUrl", "/api/v1/trust/verify");
  }
}
