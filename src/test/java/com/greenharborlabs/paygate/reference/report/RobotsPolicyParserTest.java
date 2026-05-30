package com.greenharborlabs.paygate.reference.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RobotsPolicyParserTest {
  private final RobotsPolicyParser parser = new RobotsPolicyParser();

  @Test
  @SuppressWarnings("unchecked")
  void parsesWildcardRulesSitemapsAndCrawlDelay() {
    Map<String, Object> result =
        parser.parse(
            """
            User-agent: *
            Disallow: /
            Crawl-delay: 10
            Sitemap: https://example.com/sitemap.xml
            """);

    Map<String, Object> agents = (Map<String, Object>) result.get("agents");
    Map<String, Object> wildcard = (Map<String, Object>) agents.get("*");
    assertThat(wildcard)
        .containsEntry("allowed", false)
        .containsEntry("matchedRule", "disallow:/")
        .containsEntry("crawlDelay", "10");
    assertThat((List<String>) result.get("sitemaps")).containsExactly("https://example.com/sitemap.xml");
  }

  @Test
  @SuppressWarnings("unchecked")
  void prefersSpecificAiAgentGroupOverWildcard() {
    Map<String, Object> result =
        parser.parse(
            """
            User-agent: *
            Disallow: /

            User-agent: GPTBot
            Allow: /
            """);

    Map<String, Object> agents = (Map<String, Object>) result.get("agents");
    assertThat((Map<String, Object>) agents.get("*")).containsEntry("allowed", false);
    assertThat((Map<String, Object>) agents.get("GPTBot")).containsEntry("allowed", true);
  }

  @Test
  @SuppressWarnings("unchecked")
  void malformedLinesAreBoundedWarnings() {
    Map<String, Object> result = parser.parse("User-agent: *\nthis is wrong\nDisallow: /private\n");

    assertThat(result).containsEntry("status", "warn");
    assertThat((List<String>) result.get("warnings")).containsExactly("robots-malformed-line");
  }

  @Test
  @SuppressWarnings("unchecked")
  void malformedRulePatternsAreLiteralizedAndReported() {
    Map<String, Object> result = parser.parse("User-agent: *\nDisallow: [\n");

    Map<String, Object> agents = (Map<String, Object>) result.get("agents");
    assertThat(result).containsEntry("status", "warn");
    assertThat((List<String>) result.get("warnings")).containsExactly("robots-malformed-rule-pattern");
    assertThat((Map<String, Object>) agents.get("*")).containsEntry("allowed", true);
  }

  @Test
  @SuppressWarnings("unchecked")
  void wildcardRulesMatchWithoutTreatingRegexCharactersAsSyntax() {
    Map<String, Object> result = parser.parse("User-agent: *\nDisallow: /*.json$\nAllow: /\n");

    Map<String, Object> agents = (Map<String, Object>) result.get("agents");
    assertThat(result).containsEntry("status", "ok");
    assertThat((Map<String, Object>) agents.get("*"))
        .containsEntry("allowed", true)
        .containsEntry("matchedRule", "allow:/");
  }
}
