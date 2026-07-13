package com.greenharborlabs.paygate.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DomainValidatorTest {
  private final DomainValidator validator = new DomainValidator();

  @Test
  void normalizesAsciiIUnderTurkishDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(validator.normalize("I.EXAMPLE")).isEqualTo("i.example");
      assertThat(TrustCheck.parse("RISK")).isEqualTo(TrustCheck.RISK);
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void normalizesIdn() {
    assertThat(validator.normalize("bücher.de")).isEqualTo("xn--bcher-kva.de");
  }

  @Test
  void rejectsRawIp() {
    assertThatThrownBy(() -> validator.normalize("127.0.0.1")).isInstanceOf(RuntimeException.class);
  }

  @Test
  void preservesMalformedDomainCause() {
    assertThatThrownBy(() -> validator.normalize("bad_domain.example"))
        .isInstanceOf(ApiProblem.class)
        .hasMessage("Malformed domain.")
        .cause()
        .isInstanceOf(IllegalArgumentException.class);
  }
}
