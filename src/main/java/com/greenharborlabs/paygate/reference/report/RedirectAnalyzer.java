package com.greenharborlabs.paygate.reference.report;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.dns.DnsVettingService;
import com.greenharborlabs.paygate.reference.http.SafeHttpClient;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;

public class RedirectAnalyzer {
  private static final int MAX_HOPS = 5;
  private static final int MAX_CROSS_HOST_REDIRECTS = 2;

  private final DnsVettingService dnsVettingService;
  private final SafeHttpClient safeHttpClient;

  public RedirectAnalyzer(DnsVettingService dnsVettingService, SafeHttpClient safeHttpClient) {
    this.dnsVettingService = dnsVettingService;
    this.safeHttpClient = safeHttpClient;
  }

  public Map<String, Object> analyze(String domain, List<InetAddress> initialAddresses) {
    List<Map<String, Object>> hops = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Set<String> visited = new LinkedHashSet<>();
    URI current = URI.create("https://" + domain + "/");
    List<InetAddress> addresses = initialAddresses;
    int crossHostRedirects = 0;
    Integer terminalStatus = null;

    for (int hopIndex = 0; hopIndex < MAX_HOPS; hopIndex++) {
      String currentHost = current.getHost();
      if (!visited.add(current.toString())) {
        warnings.add("redirect-loop");
        break;
      }
      try {
        var response = safeHttpClient.fetch(currentHost, addresses, HttpMethod.GET, pathAndQuery(current));
        terminalStatus = response.statusCode();
        String location = response.headers().getOrDefault("Location", response.headers().get("location"));
        boolean redirect = response.statusCode() >= 300 && response.statusCode() < 400 && location != null && !location.isBlank();
        URI next = redirect ? resolveLocation(current, location, warnings) : null;
        String nextHost = next == null ? null : next.getHost();
        boolean hostChanged = nextHost != null && !nextHost.equalsIgnoreCase(currentHost);

        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("url", current.toString());
        hop.put("status", response.statusCode());
        if (location != null) hop.put("location", location);
        hop.put("hostChanged", hostChanged);
        hops.add(hop);

        if (!redirect) break;
        if (next == null) break;
        if (!"https".equalsIgnoreCase(next.getScheme())) {
          warnings.add("redirect-non-https");
          break;
        }
        if (hostChanged) {
          crossHostRedirects++;
          if (crossHostRedirects > MAX_CROSS_HOST_REDIRECTS) {
            warnings.add("redirect-cross-host-limit");
            break;
          }
        }
        try {
          addresses = dnsVettingService.resolvePublic(nextHost);
        } catch (ApiProblem problem) {
          warnings.add("redirect-unsafe-target");
          Map<String, Object> blocked = new LinkedHashMap<>();
          blocked.put("url", next.toString());
          blocked.put("status", "blocked");
          blocked.put("reason", problem.code());
          blocked.put("hostChanged", hostChanged);
          hops.add(blocked);
          terminalStatus = null;
          break;
        }
        current = next;
      } catch (ApiProblem problem) {
        warnings.add("redirect-fetch-failed");
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("url", current.toString());
        failed.put("status", "failed");
        failed.put("reason", problem.code());
        failed.put("hostChanged", false);
        hops.add(failed);
        break;
      }
    }

    if (hops.size() >= MAX_HOPS && lastHopIsRedirect(hops)) {
      warnings.add("redirect-max-hops");
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", warnings.isEmpty() ? "ok" : "warn");
    result.put("hops", hops);
    result.put("terminalStatus", terminalStatus);
    result.put("warnings", warnings);
    return result;
  }

  private boolean lastHopIsRedirect(List<Map<String, Object>> hops) {
    Object status = hops.getLast().get("status");
    return status instanceof Integer code && code >= 300 && code < 400;
  }

  private URI resolveLocation(URI current, String location, List<String> warnings) {
    try {
      return current.resolve(location);
    } catch (IllegalArgumentException ex) {
      warnings.add("redirect-malformed-location");
      return null;
    }
  }

  private String pathAndQuery(URI uri) {
    String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
    return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
  }
}
