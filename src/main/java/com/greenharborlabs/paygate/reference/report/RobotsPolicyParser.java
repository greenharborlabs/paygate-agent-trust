package com.greenharborlabs.paygate.reference.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RobotsPolicyParser {
  private static final List<String> AI_AGENTS = List.of("GPTBot", "ChatGPT-User", "ClaudeBot", "Google-Extended", "PerplexityBot");

  public Map<String, Object> parse(String body) {
    List<Group> groups = new ArrayList<>();
    List<String> sitemaps = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Group current = null;

    for (String rawLine : body.lines().toList()) {
      String line = stripComment(rawLine).trim();
      if (line.isEmpty()) {
        current = null;
        continue;
      }
      int colon = line.indexOf(':');
      if (colon < 1) {
        warnings.add("robots-malformed-line");
        continue;
      }
      String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      switch (field) {
        case "user-agent" -> {
          if (current == null || !current.rules.isEmpty()) {
            current = new Group();
            groups.add(current);
          }
          current.agents.add(value.toLowerCase(Locale.ROOT));
        }
        case "allow", "disallow" -> {
          if (current == null) {
            warnings.add("robots-rule-without-agent");
          } else {
            if (hasUnbalancedSquareBracket(value)) {
              warnings.add("robots-malformed-rule-pattern");
            }
            current.rules.add(new Rule(field.equals("allow"), value));
          }
        }
        case "crawl-delay" -> {
          if (current == null) {
            warnings.add("robots-crawl-delay-without-agent");
          } else {
            current.crawlDelay = value;
          }
        }
        case "sitemap" -> sitemaps.add(value);
        default -> {
        }
      }
    }

    Map<String, Object> agents = new LinkedHashMap<>();
    agents.put("*", policyFor("*", groups));
    for (String agent : AI_AGENTS) {
      agents.put(agent, policyFor(agent, groups));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", warnings.isEmpty() ? "ok" : "warn");
    result.put("agents", agents);
    result.put("sitemaps", sitemaps);
    result.put("warnings", warnings);
    return result;
  }

  private Map<String, Object> policyFor(String agent, List<Group> groups) {
    Group group = bestGroup(agent, groups);
    boolean allowed = true;
    String matchedRule = null;
    String crawlDelay = null;
    if (group != null) {
      crawlDelay = group.crawlDelay;
      Rule rule = bestRule("/", group.rules);
      if (rule != null && !rule.path.isBlank()) {
        allowed = rule.allow;
        matchedRule = (rule.allow ? "allow:" : "disallow:") + rule.path;
      }
    }
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("allowed", allowed);
    if (matchedRule != null) policy.put("matchedRule", matchedRule);
    if (crawlDelay != null) policy.put("crawlDelay", crawlDelay);
    return policy;
  }

  private Group bestGroup(String agent, List<Group> groups) {
    String normalized = agent.toLowerCase(Locale.ROOT);
    Group wildcard = null;
    for (Group group : groups) {
      if (group.agents.contains(normalized)) return group;
      if (group.agents.contains("*")) wildcard = group;
    }
    return wildcard;
  }

  private Rule bestRule(String path, List<Rule> rules) {
    Rule best = null;
    for (Rule rule : rules) {
      if (rule.path.isBlank()) continue;
      if (!pathMatches(path, rule.path)) continue;
      if (best == null || rule.path.length() > best.path.length() || (rule.path.length() == best.path.length() && rule.allow)) {
        best = rule;
      }
    }
    return best;
  }

  private boolean pathMatches(String path, String pattern) {
    boolean anchoredEnd = pattern.endsWith("$");
    String glob = anchoredEnd ? pattern.substring(0, pattern.length() - 1) : pattern;
    return globMatches(path, glob, anchoredEnd);
  }

  private boolean globMatches(String path, String glob, boolean anchoredEnd) {
    int pathIndex = 0;
    int globIndex = 0;
    int starIndex = -1;
    int starPathIndex = 0;

    while (pathIndex < path.length()) {
      if (!anchoredEnd && globIndex == glob.length()) {
        return true;
      }
      if (globIndex < glob.length() && glob.charAt(globIndex) == '*') {
        starIndex = globIndex++;
        starPathIndex = pathIndex;
      } else if (globIndex < glob.length() && glob.charAt(globIndex) == path.charAt(pathIndex)) {
        globIndex++;
        pathIndex++;
      } else if (starIndex >= 0) {
        globIndex = starIndex + 1;
        pathIndex = ++starPathIndex;
      } else {
        return false;
      }
    }

    while (globIndex < glob.length() && glob.charAt(globIndex) == '*') {
      globIndex++;
    }
    return globIndex == glob.length();
  }

  private boolean hasUnbalancedSquareBracket(String value) {
    return value.indexOf('[') >= 0 || value.indexOf(']') >= 0;
  }

  private String stripComment(String line) {
    int comment = line.indexOf('#');
    return comment < 0 ? line : line.substring(0, comment);
  }

  private static class Group {
    private final List<String> agents = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private String crawlDelay;
  }

  private record Rule(boolean allow, String path) {}
}
