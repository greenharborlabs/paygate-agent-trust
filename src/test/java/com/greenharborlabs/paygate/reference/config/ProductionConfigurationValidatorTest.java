package com.greenharborlabs.paygate.reference.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTest {
  @Test
  void acceptsSecureProductionConfiguration() throws Exception {
    Fixture fixture = fixture();
    assertThatCode(fixture.validator()::validate).doesNotThrowAnyException();
  }

  @Test
  void rejectsDisabledPaymentsTestModeMemoryKeysAndInsecureLnbits() throws Exception {
    Fixture fixture = fixture();
    fixture.environment()
        .setProperty("paygate.enabled", "false");
    fixture.environment().setProperty("paygate.test-mode", "true");
    fixture.environment().setProperty("paygate.root-key-store", "memory");
    fixture.environment().setProperty("paygate.lnbits.url", "http://lnbits.internal");
    fixture.environment().setProperty("paygate.lnbits.api-key", "changeme");
    fixture.environment().setProperty("paygate.protocols.mpp.challenge-binding-secret", "short");

    assertThatThrownBy(fixture.validator()::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("paygate.enabled")
        .hasMessageContaining("paygate.test-mode")
        .hasMessageContaining("paygate.root-key-store")
        .hasMessageContaining("absolute HTTPS URL")
        .hasMessageContaining("challenge-binding-secret");
  }

  @Test
  void rejectsMalformedAndMismatchedSigningKeysAndLocalKeyId() throws Exception {
    KeyPair first = keyPair();
    KeyPair second = keyPair();
    MockEnvironment environment = productionEnvironment();
    PaygateReferenceProperties properties = properties(first, second, "local-dev");

    assertThatThrownBy(() -> new ProductionConfigurationValidator(environment, properties).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("do not match")
        .hasMessageContaining("non-local production identifier");

    PaygateReferenceProperties malformed = properties("not-base64", "also-not-base64", "2026-07-prod");
    assertThatThrownBy(() -> new ProductionConfigurationValidator(environment, malformed).validate())
        .hasMessageContaining("valid Base64 DER Ed25519");
  }

  private Fixture fixture() throws Exception {
    MockEnvironment environment = productionEnvironment();
    KeyPair pair = keyPair();
    var validator = new ProductionConfigurationValidator(environment, properties(pair, pair, "2026-07-prod"));
    return new Fixture(environment, validator);
  }

  private MockEnvironment productionEnvironment() {
    return new MockEnvironment()
        .withProperty("paygate.enabled", "true")
        .withProperty("paygate.backend", "lnbits")
        .withProperty("paygate.test-mode", "false")
        .withProperty("paygate.root-key-store", "file")
        .withProperty("paygate.lnbits.url", "https://lnbits.greenharbor.example.net")
        .withProperty("paygate.lnbits.api-key", "production-api-key")
        .withProperty("paygate.protocols.mpp.challenge-binding-secret", "0123456789abcdef0123456789abcdef");
  }

  private KeyPair keyPair() throws Exception {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private PaygateReferenceProperties properties(KeyPair privatePair, KeyPair publicPair, String keyId) {
    return properties(
        Base64.getEncoder().encodeToString(privatePair.getPrivate().getEncoded()),
        Base64.getEncoder().encodeToString(publicPair.getPublic().getEncoded()),
        keyId);
  }

  private PaygateReferenceProperties properties(String privateKey, String publicKey, String keyId) {
    return new PaygateReferenceProperties(1, 2, 3, 6, 65536, 8192, 32, 15, 16, privateKey, publicKey, keyId);
  }

  private record Fixture(MockEnvironment environment, ProductionConfigurationValidator validator) {}
}
