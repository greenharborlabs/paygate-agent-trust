package com.greenharborlabs.paygate.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TrustReportRequestParserTest {
  private final TrustReportRequestParser parser = new TrustReportRequestParser(new DomainValidator());

  @Test
  void parsesDefaultChecks() {
    var req = parser.parse("example.com", null);
    assertThat(req.normalizedDomain()).isEqualTo("example.com");
    assertThat(req.checks()).containsExactly(TrustCheck.DNS);
  }

  @Test
  void rejectsUnsupportedCheck() {
    assertThatThrownBy(() -> parser.parse("example.com", "dns,nope")).isInstanceOf(RuntimeException.class);
  }
}
