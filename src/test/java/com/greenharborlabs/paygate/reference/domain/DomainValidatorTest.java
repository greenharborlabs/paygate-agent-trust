package com.greenharborlabs.paygate.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DomainValidatorTest {
  private final DomainValidator validator = new DomainValidator();

  @Test
  void normalizesIdn() {
    assertThat(validator.normalize("bücher.de")).isEqualTo("xn--bcher-kva.de");
  }

  @Test
  void rejectsRawIp() {
    assertThatThrownBy(() -> validator.normalize("127.0.0.1")).isInstanceOf(RuntimeException.class);
  }
}
