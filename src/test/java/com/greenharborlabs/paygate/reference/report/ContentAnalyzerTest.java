package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentAnalyzerTest {
  private final ContentAnalyzer analyzer = new ContentAnalyzer();

  @Test
  @SuppressWarnings("unchecked")
  void detectsHtmlLoginPaywallAndNoindexWithoutExecutingContent() {
    String html =
        """
        <html>
          <head><meta name="robots" content="noindex,nofollow"><script>alert('ignored')</script></head>
          <body>
            <form action="/login"><input type="email"><input type="password"></form>
            Subscribe now to continue reading this subscriber-only article.
          </body>
        </html>
        """;

    Map<String, Object> result =
        analyzer.analyze(Map.of("Content-Type", "text/html; charset=utf-8"), html);

    assertThat(result)
        .containsEntry("analyzed", true)
        .containsEntry("contentType", "text/html; charset=utf-8")
        .containsEntry("kind", "html");
    assertThat((Map<String, Object>) result.get("login")).containsEntry("detected", true);
    assertThat((Map<String, Object>) result.get("paywall")).containsEntry("detected", true);
    assertThat((Map<String, Object>) result.get("robots")).containsEntry("noindex", true);
    assertThat((String) result.get("snippet")).hasSizeLessThanOrEqualTo(512);
    assertThat((List<String>) result.get("warnings"))
        .contains("login-form-detected", "paywall-detected", "noindex-detected");
  }

  @Test
  @SuppressWarnings("unchecked")
  void classifiesJsonApiContent() {
    Map<String, Object> result =
        analyzer.analyze(Map.of("Content-Type", "application/json"), "{\"ok\":true,\"items\":[]}");

    assertThat(result).containsEntry("analyzed", true).containsEntry("kind", "api_json");
    assertThat((Map<String, Object>) result.get("login")).containsEntry("detected", false);
    assertThat((Map<String, Object>) result.get("paywall")).containsEntry("detected", false);
  }

  @Test
  void reportsBinaryContentAsUnsupported() {
    Map<String, Object> result =
        analyzer.analyze(Map.of("Content-Type", "application/octet-stream"), "\u0000\u0001\u0002");

    assertThat(result)
        .containsEntry("status", "warn")
        .containsEntry("analyzed", false)
        .containsEntry("reason", "unsupported_binary_content")
        .containsEntry("contentType", "application/octet-stream");
    assertThat(result).doesNotContainKey("snippet");
    assertThat(result).containsEntry("warnings", List.of("unsupported-content"));
  }

  @Test
  void reportsUnknownNonBinaryContentAsUnsupported() {
    Map<String, Object> result =
        analyzer.analyze(Map.of("Content-Type", "application/vnd.example.custom"), "status=ok");

    assertThat(result)
        .containsEntry("status", "warn")
        .containsEntry("analyzed", false)
        .containsEntry("reason", "unsupported_content")
        .containsEntry("contentType", "application/vnd.example.custom");
    assertThat(result).doesNotContainKey("snippet");
    assertThat(result).containsEntry("warnings", List.of("unsupported-content"));
  }
}
