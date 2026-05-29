package com.greenharborlabs.paygate.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TrustReportRequestParserTest {
  private final TrustReportRequestParser parser = new TrustReportRequestParser(new DomainValidator());

  @Test
  void omittedChecksUseComprehensiveDefault() {
    var req = parser.parse("example.com", null);
    assertThat(req.normalizedDomain()).isEqualTo("example.com");
    assertThat(req.checks())
        .containsExactly(
            TrustCheck.DNS,
            TrustCheck.TLS,
            TrustCheck.HTTP,
            TrustCheck.REDIRECTS,
            TrustCheck.ROBOTS,
            TrustCheck.SECURITY_HEADERS,
            TrustCheck.CONTENT,
            TrustCheck.RISK);
  }

  @Test
  void blankAndEmptyTokenListsUseComprehensiveDefault() {
    assertThat(parser.parse("example.com", "   ").checks())
        .containsExactlyElementsOf(TrustCheck.comprehensiveDefault());
    assertThat(parser.parse("example.com", " , , ").checks())
        .containsExactlyElementsOf(TrustCheck.comprehensiveDefault());
  }

  @Test
  void explicitSubsetDoesNotExpandToDefaultAndDeduplicates() {
    var req = parser.parse("example.com", "dns,tls,dns");
    assertThat(req.checks()).containsExactly(TrustCheck.DNS, TrustCheck.TLS);
  }

  @Test
  void rejectsUnsupportedCheck() {
    assertThatThrownBy(() -> parser.parse("example.com", "dns,nope"))
        .isInstanceOf(ApiProblem.class)
        .satisfies(
            ex -> {
              ApiProblem problem = (ApiProblem) ex;
              assertThat(problem.code()).isEqualTo("UNSUPPORTED_CHECK");
              assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
  }
}
