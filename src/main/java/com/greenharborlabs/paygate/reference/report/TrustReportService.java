package com.greenharborlabs.paygate.reference.report;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import com.greenharborlabs.paygate.reference.domain.TrustCheck;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequest;
import com.greenharborlabs.paygate.reference.http.SafeHttpClient;
import com.greenharborlabs.paygate.reference.http.TlsCertificateInspector;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

@Service
public class TrustReportService {
  private final DnsVettingService dnsVettingService;
  private final SafeHttpClient safeHttpClient;
  private final ReportSigner reportSigner;
  private final ReceiptBindingService receiptBindingService;
  private final PaygateReferenceProperties properties;
  private final TlsCertificateInspector tlsCertificateInspector;
  private final RedirectAnalyzer redirectAnalyzer;
  private final RobotsPolicyParser robotsPolicyParser;
  private final SecurityHeadersAnalyzer securityHeadersAnalyzer;
  private final ContentAnalyzer contentAnalyzer;
  private final RiskScoringService riskScoringService;
  private final Cache<String, Map<String, Object>> cache;

  public TrustReportService(
      DnsVettingService dnsVettingService,
      SafeHttpClient safeHttpClient,
      ReportSigner reportSigner,
      ReceiptBindingService receiptBindingService,
      PaygateReferenceProperties properties) {
    this.dnsVettingService = dnsVettingService;
    this.safeHttpClient = safeHttpClient;
    this.reportSigner = reportSigner;
    this.receiptBindingService = receiptBindingService;
    this.properties = properties;
    this.tlsCertificateInspector = new TlsCertificateInspector(Duration.ofSeconds(properties.connectTimeoutSeconds()));
    this.redirectAnalyzer = new RedirectAnalyzer(dnsVettingService, safeHttpClient);
    this.robotsPolicyParser = new RobotsPolicyParser();
    this.securityHeadersAnalyzer = new SecurityHeadersAnalyzer();
    this.contentAnalyzer = new ContentAnalyzer();
    this.riskScoringService = new RiskScoringService();
    this.cache = Caffeine.newBuilder().expireAfterWrite(properties.cacheTtl()).maximumSize(5000).build();
  }

  public Map<String, Object> createReport(TrustReportRequest request) {
    String cacheKey = request.normalizedDomain() + "|" + request.checks();
    Map<String, Object> cached = cache.getIfPresent(cacheKey);
    if (cached != null) {
      Map<String, Object> copy = new LinkedHashMap<>(cached);
      copy.put("cache", Map.of("hit", true, "ttlSeconds", 900));
      return copy;
    }
    List<InetAddress> addresses = dnsVettingService.resolvePublic(request.normalizedDomain());
    Map<String, Object> checks = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    if (request.checks().contains(TrustCheck.DNS)) {
      checks.put("dns", Map.of("answers", addresses.stream().map(InetAddress::getHostAddress).toList()));
    }
    SafeHttpClient.SafeHttpResponse rootResponse = null;
    if (request.checks().contains(TrustCheck.HTTP)) {
      rootResponse = safeHttpClient.fetch(request.normalizedDomain(), addresses, HttpMethod.GET, "/");
      checks.put("http", Map.of("statusCode", rootResponse.statusCode(), "headers", rootResponse.headers()));
      if (rootResponse.statusCode() >= 300 && rootResponse.statusCode() < 400) warnings.add("redirect-not-followed");
    }
    if (request.checks().contains(TrustCheck.ROBOTS)) {
      Map<String, Object> robots = robotsCheck(request.normalizedDomain(), addresses);
      checks.put("robots", robots);
      warnings.addAll(checkWarnings(robots, "robots"));
    }
    if (request.checks().contains(TrustCheck.TLS)) {
      Map<String, Object> tls = tlsCertificateInspector.inspect(request.normalizedDomain(), addresses);
      checks.put("tls", tls);
      warnings.addAll(checkWarnings(tls, "tls"));
    }
    if (request.checks().contains(TrustCheck.REDIRECTS)) {
      Map<String, Object> redirects = redirectAnalyzer.analyze(request.normalizedDomain(), addresses);
      checks.put("redirects", redirects);
      warnings.addAll(checkWarnings(redirects, "redirects"));
    }
    addRootResponseChecks(request, checks, warnings, rootResponse, addresses);
    addRiskCheck(request, checks);
    Map<String, Object> verdict = Map.of("status", warnings.isEmpty() ? "ok" : "warn", "warnings", warnings);
    Map<String, Object> signable = new LinkedHashMap<>();
    signable.put("domain", request.normalizedDomain());
    signable.put("checkedAt", Instant.now().toString());
    signable.put("checks", checks);
    signable.put("verdict", verdict);
    var sig = reportSigner.sign(signable);

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("reportId", "tr_" + System.currentTimeMillis());
    report.put("reportDigest", sig.digest());
    report.put("signature", Map.of("algorithm", sig.algorithm(), "keyId", sig.keyId(), "value", sig.signature()));
    report.put("domain", request.normalizedDomain());
    report.put("checkedAt", signable.get("checkedAt"));
    report.put("checks", checks);
    report.put("verdict", verdict);
    report.put("cache", Map.of("hit", false, "ttlSeconds", 900));
    cache.put(cacheKey, report);
    return report;
  }

  public Map<String, Object> bindReceipt(Map<String, Object> report, String receipt) {
    if (receipt == null || receipt.isBlank()) return report;
    Object signatureValue = report.get("signature");
    if (!(report.get("reportDigest") instanceof String reportDigest)
        || !(signatureValue instanceof Map<?, ?> signature)
        || !(signature.get("value") instanceof String reportSignature)) {
      return report;
    }
    Map<String, Object> bound = new LinkedHashMap<>(report);
    bound.put("receiptBinding", receiptBindingService.bind(receipt, reportDigest, reportSignature));
    return bound;
  }

  private void addRootResponseChecks(
      TrustReportRequest request,
      Map<String, Object> checks,
      List<String> warnings,
      SafeHttpClient.SafeHttpResponse rootResponse,
      List<InetAddress> addresses) {
    boolean needsRootResponse =
        request.checks().contains(TrustCheck.SECURITY_HEADERS) || request.checks().contains(TrustCheck.CONTENT);
    if (!needsRootResponse) return;

    SafeHttpClient.SafeHttpResponse response = rootResponse;
    if (response == null) {
      try {
        response = safeHttpClient.fetch(request.normalizedDomain(), addresses, HttpMethod.GET, "/");
      } catch (ApiProblem problem) {
        Map<String, Object> failed = fetchFailedCheck(problem);
        if (request.checks().contains(TrustCheck.SECURITY_HEADERS)) {
          checks.put("security_headers", failed);
          warnings.addAll(checkWarnings(failed, "security_headers"));
        }
        if (request.checks().contains(TrustCheck.CONTENT)) {
          checks.put("content", failed);
          warnings.addAll(checkWarnings(failed, "content"));
        }
        return;
      }
    }
    if (request.checks().contains(TrustCheck.SECURITY_HEADERS)) {
      Map<String, Object> securityHeaders = securityHeadersAnalyzer.analyze(response.headers());
      checks.put("security_headers", securityHeaders);
      warnings.addAll(checkWarnings(securityHeaders, "security_headers"));
    }
    if (request.checks().contains(TrustCheck.CONTENT)) {
      Map<String, Object> content = contentAnalyzer.analyze(response.headers(), response.body());
      checks.put("content", content);
      warnings.addAll(checkWarnings(content, "content"));
    }
  }

  private void addRiskCheck(TrustReportRequest request, Map<String, Object> checks) {
    if (request.checks().contains(TrustCheck.RISK)) {
      checks.put("risk", riskScoringService.score(checks));
    }
  }

  private Map<String, Object> fetchFailedCheck(ApiProblem problem) {
    return Map.of(
        "status", "warn",
        "analyzed", false,
        "reason", problem.code(),
        "warnings", List.of("fetch-failed"));
  }

  private Map<String, Object> robotsCheck(String domain, List<InetAddress> addresses) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    try {
      var robots = safeHttpClient.fetch(domain, addresses, HttpMethod.GET, "/robots.txt");
      result.put("robotsStatus", robots.statusCode());
      if (robots.statusCode() == 404) {
        result.put("status", "missing");
      } else if (robots.statusCode() >= 200 && robots.statusCode() < 300) {
        Map<String, Object> policy = robotsPolicyParser.parse(robots.body());
        result.putAll(policy);
        if (robots.body().length() >= properties.maxBodyBytes()) {
          warnings.add("robots-body-capped");
        }
      } else {
        result.put("status", "warn");
        warnings.add("robots-fetch-status-" + robots.statusCode());
      }
    } catch (ApiProblem problem) {
      result.put("status", "warn");
      result.put("robotsStatus", "fetch_failed");
      result.put("reason", problem.code());
      warnings.add("robots-fetch-failed");
    }

    try {
      var ai = safeHttpClient.fetch(domain, addresses, HttpMethod.GET, "/ai.txt");
      result.put("aiStatus", ai.statusCode());
      if (ai.statusCode() >= 200 && ai.statusCode() < 300) {
        result.put("aiSnippet", snippet(ai.body()));
      }
    } catch (ApiProblem problem) {
      result.put("aiStatus", "fetch_failed");
      warnings.add("ai-txt-fetch-failed");
    }
    warnings.addAll(asStringList(result.get("warnings")));
    result.put("warnings", warnings.stream().distinct().toList());
    if (!result.containsKey("status")) result.put("status", warnings.isEmpty() ? "ok" : "warn");
    return result;
  }

  private List<String> checkWarnings(Map<String, Object> check, String prefix) {
    return asStringList(check.get("warnings")).stream().map(warning -> prefix + ":" + warning).toList();
  }

  private List<String> asStringList(Object value) {
    if (!(value instanceof List<?> values)) return List.of();
    return values.stream().map(String::valueOf).toList();
  }

  private String snippet(String body) {
    int limit = Math.min(body.length(), Math.min(properties.maxBodyBytes(), 512));
    return body.substring(0, limit);
  }
}
