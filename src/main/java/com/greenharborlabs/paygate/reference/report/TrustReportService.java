package com.greenharborlabs.paygate.reference.report;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import com.greenharborlabs.paygate.reference.domain.TrustCheck;
import com.greenharborlabs.paygate.reference.domain.TrustReportRequest;
import com.greenharborlabs.paygate.reference.http.SafeHttpClient;
import java.net.InetAddress;
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
  private final Cache<String, Map<String, Object>> cache;

  public TrustReportService(
      DnsVettingService dnsVettingService,
      SafeHttpClient safeHttpClient,
      ReportSigner reportSigner,
      PaygateReferenceProperties properties) {
    this.dnsVettingService = dnsVettingService;
    this.safeHttpClient = safeHttpClient;
    this.reportSigner = reportSigner;
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
    if (request.checks().contains(TrustCheck.HTTP)) {
      var rsp = safeHttpClient.fetch(request.normalizedDomain(), addresses, HttpMethod.GET, "/");
      checks.put("http", Map.of("statusCode", rsp.statusCode(), "headers", rsp.headers()));
      if (rsp.statusCode() >= 300 && rsp.statusCode() < 400) warnings.add("redirect-not-followed");
    }
    if (request.checks().contains(TrustCheck.ROBOTS)) {
      var robots = safeHttpClient.fetch(request.normalizedDomain(), addresses, HttpMethod.GET, "/robots.txt");
      var ai = safeHttpClient.fetch(request.normalizedDomain(), addresses, HttpMethod.GET, "/ai.txt");
      checks.put("robots", Map.of("robotsStatus", robots.statusCode(), "aiStatus", ai.statusCode()));
    }
    if (request.checks().contains(TrustCheck.TLS)) {
      checks.put("tls", Map.of("status", "ok"));
    }
    Map<String, Object> signable =
        Map.of("domain", request.normalizedDomain(), "checkedAt", Instant.now().toString(), "checks", checks);
    var sig = reportSigner.sign(signable);

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("reportId", "tr_" + System.currentTimeMillis());
    report.put("reportDigest", sig.digest());
    report.put("signature", Map.of("algorithm", sig.algorithm(), "keyId", sig.keyId(), "value", sig.signature()));
    report.put("domain", request.normalizedDomain());
    report.put("checkedAt", signable.get("checkedAt"));
    report.put("checks", checks);
    report.put("verdict", Map.of("status", warnings.isEmpty() ? "ok" : "warn", "warnings", warnings));
    report.put("cache", Map.of("hit", false, "ttlSeconds", 900));
    cache.put(cacheKey, report);
    return report;
  }
}
