package com.greenharborlabs.paygate.reference.http;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import com.greenharborlabs.paygate.reference.config.PaygateReferenceProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SafeHttpClient {
  public record SafeHttpResponse(int statusCode, Map<String, String> headers, String body) {}

  private final PaygateReferenceProperties properties;

  public SafeHttpClient(PaygateReferenceProperties properties) {
    this.properties = properties;
  }

  public SafeHttpResponse fetch(
      String domain, List<InetAddress> vettedAddresses, HttpMethod method, String path) {
    try (CloseableHttpClient hc = buildClient(domain, vettedAddresses)) {
      RestClient client =
          RestClient.builder()
              .requestFactory(new HttpComponentsClientHttpRequestFactory(hc))
              .build();
      return
          client
              .method(method)
              .uri(URI.create("https://" + domain + path))
              .exchange(
                  (request, response) ->
                      new SafeHttpResponse(
                          response.getStatusCode().value(),
                          limitedHeaders(response.getHeaders()),
                          limitedBody(response.getBody())));
    } catch (Exception ex) {
      throw mapException(ex);
    }
  }

  private Map<String, String> limitedHeaders(HttpHeaders responseHeaders) {
    Map<String, String> headers = new LinkedHashMap<>();
    int totalBytes = 0;
    for (Map.Entry<String, List<String>> entry : responseHeaders.headerSet()) {
      if (headers.size() >= properties.maxHeadersCount()) break;
      String name = entry.getKey();
      List<String> values = entry.getValue();
      String value = String.join(",", values);
      totalBytes += name.length() + value.length();
      if (totalBytes > properties.maxHeadersBytes()) break;
      headers.put(name, value);
    }
    return headers;
  }

  private String limitedBody(InputStream body) throws IOException {
    byte[] bytes = body.readNBytes(properties.maxBodyBytes() + 1);
    if (bytes.length > properties.maxBodyBytes()) {
      return new String(bytes, 0, properties.maxBodyBytes(), StandardCharsets.UTF_8);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private CloseableHttpClient buildClient(String domain, List<InetAddress> vettedAddresses) {
    DnsResolver resolver =
        new DnsResolver() {
          @Override
          public InetAddress[] resolve(String host) {
            return host.equalsIgnoreCase(domain) ? vettedAddresses.toArray(InetAddress[]::new) : new InetAddress[0];
          }

          @Override
          public String resolveCanonicalHostname(String host) {
            return host;
          }
        };
    RequestConfig config =
        RequestConfig.custom()
            .setRedirectsEnabled(false)
            .setResponseTimeout(Timeout.ofSeconds(properties.readTimeoutSeconds()))
            .build();
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(properties.connectTimeoutSeconds()))
            .build();
    var cm =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setDefaultConnectionConfig(connectionConfig)
            .build();
    return HttpClients.custom().setConnectionManager(cm).setDefaultRequestConfig(config).disableCookieManagement().build();
  }

  private RuntimeException mapException(Exception ex) {
    Throwable cursor = ex;
    var queue = new ArrayDeque<Throwable>();
    while (cursor != null) {
      queue.add(cursor);
      cursor = cursor.getCause();
    }
    for (Throwable t : queue) {
      if (t instanceof SocketTimeoutException) {
        return new ApiProblem("TARGET_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, true, "Target timeout.");
      }
      if (t instanceof SSLException) {
        return new ApiProblem("TARGET_TLS_FAILED", HttpStatus.BAD_GATEWAY, true, "Target TLS failed.");
      }
      if (t instanceof IOException) {
        return new ApiProblem("TARGET_FETCH_FAILED", HttpStatus.BAD_GATEWAY, true, "Target fetch failed.");
      }
    }
    return new ApiProblem("TARGET_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, true, "Target timeout.");
  }
}
