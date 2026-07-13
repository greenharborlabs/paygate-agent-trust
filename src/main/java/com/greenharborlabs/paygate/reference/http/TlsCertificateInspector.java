package com.greenharborlabs.paygate.reference.http;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class TlsCertificateInspector {
  private static final int HTTPS_PORT = 443;
  private static final long NEAR_EXPIRY_DAYS = 30;

  private final Clock clock;
  private final Duration connectTimeout;
  private final SSLSocketFactory primarySocketFactory;
  private final SSLSocketFactory diagnosticSocketFactory;

  public TlsCertificateInspector(Duration connectTimeout) {
    this(
        Clock.systemUTC(),
        connectTimeout,
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        diagnosticSocketFactory());
  }

  TlsCertificateInspector(Clock clock, Duration connectTimeout) {
    this(
        clock,
        connectTimeout,
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        diagnosticSocketFactory());
  }

  TlsCertificateInspector(
      Clock clock,
      Duration connectTimeout,
      SSLSocketFactory primarySocketFactory,
      SSLSocketFactory diagnosticSocketFactory) {
    this.clock = clock;
    this.connectTimeout = connectTimeout;
    this.primarySocketFactory = primarySocketFactory;
    this.diagnosticSocketFactory = diagnosticSocketFactory;
  }

  public Map<String, Object> inspect(String domain, List<InetAddress> vettedAddresses) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    InetAddress address = vettedAddresses.getFirst();
    try {
      try (SSLSocket socket = newSocket(primarySocketFactory)) {
        connectAndHandshake(socket, domain, address);
        SSLSession session = socket.getSession();
        X509Certificate certificate = leafCertificate(session);
        result.putAll(certificateMetadata(certificate, domain, session, warnings));
      }
      result.put("status", warnings.isEmpty() ? "ok" : "warn");
    } catch (SSLPeerUnverifiedException ex) {
      result.putAll(certificateInspectionFailure(ex, warnings));
    } catch (SSLException ex) {
      result.putAll(tlsHandshakeFailure(ex, warnings));
      if (hasCertificateTimeValidityFailure(ex)) {
        enrichWithDiagnosticCertificate(result, warnings, domain, address);
      }
    } catch (IOException | RuntimeException ex) {
      result.putAll(certificateInspectionFailure(ex, warnings));
    }
    result.put("warnings", warnings);
    return result;
  }

  Map<String, Object> inspectCertificate(String domain, X509Certificate certificate, SSLSession session) {
    List<String> warnings = new ArrayList<>();
    Map<String, Object> result = certificateMetadata(certificate, domain, session, warnings);
    result.put("status", warnings.isEmpty() ? "ok" : "warn");
    result.put("warnings", warnings);
    return result;
  }

  Map<String, Object> certificateInspectionFailure(Exception ex, List<String> warnings) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "failed");
    result.put("retryable", false);
    result.put("reason", "certificate_inspection_failed");
    result.put("message", "Certificate inspection failed.");
    warnings.add("certificate-parsing-failed");
    return result;
  }

  Map<String, Object> tlsHandshakeFailure(SSLException ex, List<String> warnings) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "failed");
    result.put("retryable", true);
    result.put("reason", "TARGET_TLS_FAILED");
    result.put("message", "TLS handshake failed.");
    warnings.add("tls-handshake-failed");
    return result;
  }

  private Optional<DiagnosticCertificate> diagnosticLeafCertificate(
      String domain, InetAddress address, List<String> warnings) {
    // Diagnostic-only: default JVM validation has already failed and remains authoritative.
    // This scoped non-validating socket is used only to collect peer certificate metadata.
    try (SSLSocket socket = newSocket(diagnosticSocketFactory)) {
      connectAndHandshake(socket, domain, address);
      SSLSession session = socket.getSession();
      return Optional.of(new DiagnosticCertificate(leafCertificate(session), session));
    } catch (IOException ex) {
      return Optional.empty();
    } catch (RuntimeException ex) {
      warnings.add("tls-diagnostic-failed");
      return Optional.empty();
    }
  }

  private void enrichWithDiagnosticCertificate(
      Map<String, Object> result, List<String> warnings, String domain, InetAddress address) {
    diagnosticLeafCertificate(domain, address, warnings)
        .ifPresent(
            diagnostic -> {
              List<String> diagnosticWarnings = new ArrayList<>(warnings);
              try {
                Map<String, Object> metadata =
                    certificateMetadata(diagnostic.certificate(), domain, diagnostic.session(), diagnosticWarnings);
                result.putAll(metadata);
                warnings.clear();
                warnings.addAll(diagnosticWarnings);
              } catch (RuntimeException ex) {
                // Diagnostic enrichment must never replace the authoritative TLS failure.
                warnings.add("tls-diagnostic-failed");
              }
            });
  }

  private void connectAndHandshake(SSLSocket socket, String domain, InetAddress address) throws java.io.IOException {
    SSLParameters parameters = socket.getSSLParameters();
    parameters.setServerNames(List.of(new SNIHostName(domain)));
    socket.setSSLParameters(parameters);
    int timeoutMillis = timeoutMillis();
    socket.connect(new InetSocketAddress(address, HTTPS_PORT), timeoutMillis);
    socket.setSoTimeout(timeoutMillis);
    socket.startHandshake();
  }

  // Ownership transfers to the caller for an SSLSocket; rejected socket types are closed here.
  @SuppressWarnings("PMD.CloseResource")
  private SSLSocket newSocket(SSLSocketFactory factory) throws java.io.IOException {
    Socket socket = factory.createSocket();
    if (socket instanceof SSLSocket sslSocket) {
      return sslSocket;
    }
    socket.close();
    throw new SSLException("Socket factory did not create an SSL socket");
  }

  private boolean hasCertificateTimeValidityFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof CertificateExpiredException || current instanceof CertificateNotYetValidException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private Map<String, Object> certificateMetadata(
      X509Certificate certificate, String domain, SSLSession session, List<String> warnings) {
    Map<String, Object> result = new LinkedHashMap<>();
    Instant now = clock.instant();
    Instant notBefore = certificate.getNotBefore().toInstant();
    Instant notAfter = certificate.getNotAfter().toInstant();
    long daysUntilExpiry =
        ChronoUnit.DAYS.between(
            now.atZone(ZoneOffset.UTC).toLocalDate(), notAfter.atZone(ZoneOffset.UTC).toLocalDate());

    if (notAfter.isBefore(now)) {
      warnings.add("certificate-expired");
    } else if (daysUntilExpiry <= NEAR_EXPIRY_DAYS) {
      warnings.add("certificate-near-expiry");
    }
    if (notBefore.isAfter(now)) {
      warnings.add("certificate-not-yet-valid");
    }
    if (session != null && !hostnameMatches(domain, session)) {
      warnings.add("certificate-hostname-mismatch");
    }

    result.put("subject", certificate.getSubjectX500Principal().getName());
    result.put("issuer", certificate.getIssuerX500Principal().getName());
    result.put("sanDnsNames", sanDnsNames(certificate));
    result.put("serial", serial(certificate.getSerialNumber()));
    result.put("validFrom", notBefore.toString());
    result.put("validUntil", notAfter.toString());
    result.put("daysUntilExpiry", daysUntilExpiry);
    result.put("signatureAlgorithm", certificate.getSigAlgName());
    result.put("expired", notAfter.isBefore(now));
    result.put("notYetValid", notBefore.isAfter(now));
    result.put("nearExpiry", !notAfter.isBefore(now) && daysUntilExpiry <= NEAR_EXPIRY_DAYS);
    result.put("hostnameMatched", session == null || hostnameMatches(domain, session));
    return result;
  }

  private X509Certificate leafCertificate(SSLSession session) throws SSLPeerUnverifiedException {
    Certificate[] certificates = session.getPeerCertificates();
    if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate certificate)) {
      throw new SSLPeerUnverifiedException("No X.509 peer certificate");
    }
    return certificate;
  }

  private boolean hostnameMatches(String domain, SSLSession session) {
    HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
    return verifier.verify(domain, session);
  }

  private List<String> sanDnsNames(X509Certificate certificate) {
    try {
      Collection<List<?>> names = certificate.getSubjectAlternativeNames();
      if (names == null) return List.of();
      List<String> dnsNames = new ArrayList<>();
      for (List<?> name : names) {
        if (name.size() >= 2 && Integer.valueOf(2).equals(name.getFirst())) {
          dnsNames.add(String.valueOf(name.get(1)).toLowerCase(Locale.ROOT));
        }
      }
      return dnsNames;
    } catch (CertificateParsingException ex) {
      return List.of();
    }
  }

  private String serial(BigInteger serialNumber) {
    return serialNumber.toString(16).toUpperCase(Locale.ROOT);
  }

  private int timeoutMillis() {
    return Math.toIntExact(connectTimeout.toMillis());
  }

  private static SSLSocketFactory diagnosticSocketFactory() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {new DiagnosticTrustManager()}, new SecureRandom());
      return context.getSocketFactory();
    } catch (GeneralSecurityException ex) {
      return (SSLSocketFactory) SSLSocketFactory.getDefault();
    }
  }

  private record DiagnosticCertificate(X509Certificate certificate, SSLSession session) {}

  private static final class DiagnosticTrustManager implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
