package com.greenharborlabs.paygate.reference.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails production startup before serving traffic when security-critical configuration is unsafe. */
@Component
@Profile("prod")
public final class ProductionConfigurationValidator implements ApplicationRunner {
  private static final byte[] KEY_PROBE = "paygate-agent-trust-key-pair-check".getBytes(StandardCharsets.UTF_8);
  private final Environment environment;
  private final PaygateReferenceProperties reference;

  public ProductionConfigurationValidator(Environment environment, PaygateReferenceProperties reference) {
    this.environment = environment;
    this.reference = reference;
  }

  @Override
  public void run(ApplicationArguments args) {
    validate();
  }

  void validate() {
    List<String> errors = new ArrayList<>();
    requireExact("paygate.enabled", "true", errors);
    requireExact("paygate.backend", "lnbits", errors);
    rejectTrue("paygate.test-mode", errors);
    rejectExact("paygate.root-key-store", "memory", errors);
    requireSecret("paygate.lnbits.api-key", 1, errors);
    requireSecret("paygate.protocols.mpp.challenge-binding-secret", 32, errors);
    requireHttps("paygate.lnbits.url", errors);

    String keyId = reference.reportSigningKeyId();
    if (isPlaceholder(keyId)
        || keyId.toLowerCase(Locale.ROOT).contains("local")
        || keyId.toLowerCase(Locale.ROOT).contains("test")) {
      errors.add("reference.report-signing-key-id must be a stable non-local production identifier");
    }
    validateKeyPair(errors);

    if (!errors.isEmpty()) {
      throw new IllegalStateException("Unsafe production configuration:\n - " + String.join("\n - ", errors));
    }
  }

  private void validateKeyPair(List<String> errors) {
    try {
      KeyFactory factory = KeyFactory.getInstance("Ed25519");
      PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(reference.reportSigningPrivateKey())));
      PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(reference.reportSigningPublicKey())));
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(KEY_PROBE);
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(KEY_PROBE);
      if (!verifier.verify(signer.sign())) errors.add("report signing public and private keys do not match");
    } catch (IllegalArgumentException | GeneralSecurityException exception) {
      errors.add("report signing keys must be valid Base64 DER Ed25519 PKCS#8/X.509 keys");
    }
  }

  private void requireExact(String name, String expected, List<String> errors) {
    if (!expected.equalsIgnoreCase(value(name))) errors.add(name + " must be " + expected);
  }

  private void rejectTrue(String name, List<String> errors) {
    if ("true".equalsIgnoreCase(value(name))) errors.add(name + " is forbidden in production");
  }

  private void rejectExact(String name, String forbidden, List<String> errors) {
    if (forbidden.equalsIgnoreCase(value(name))) errors.add(name + "=" + forbidden + " is forbidden in production");
  }

  private void requireSecret(String name, int minimumBytes, List<String> errors) {
    String value = value(name);
    if (isPlaceholder(value) || value.getBytes(StandardCharsets.UTF_8).length < minimumBytes) {
      errors.add(name + " must be a non-default secret of at least " + minimumBytes + " bytes");
    }
  }

  private void requireHttps(String name, List<String> errors) {
    try {
      URI uri = URI.create(value(name));
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
    } catch (RuntimeException exception) {
      errors.add(name + " must be an absolute HTTPS URL");
    }
  }

  private String value(String name) {
    return environment.getProperty(name, "").trim();
  }

  private boolean isPlaceholder(String value) {
    if (value == null || value.isBlank()) return true;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.contains("<") || normalized.contains("changeme") || normalized.contains("default") || normalized.contains("example");
  }
}
