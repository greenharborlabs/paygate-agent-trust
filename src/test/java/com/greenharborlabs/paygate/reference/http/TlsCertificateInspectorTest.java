package com.greenharborlabs.paygate.reference.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.net.InetAddress;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;

class TlsCertificateInspectorTest {
  private final TlsCertificateInspector inspector =
      new TlsCertificateInspector(
          Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC), Duration.ofSeconds(1));

  @Test
  @SuppressWarnings("unchecked")
  void reportsCertificateMetadataAndNearExpiryWarning() throws Exception {
    X509Certificate certificate =
        certificate(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-10T00:00:00Z"), "CN=example.com");

    Map<String, Object> result = inspector.inspectCertificate("example.com", certificate, null);

    assertThat(result)
        .containsEntry("status", "warn")
        .containsEntry("subject", "CN=example.com")
        .containsEntry("issuer", "CN=issuer")
        .containsEntry("serial", "2A")
        .containsEntry("daysUntilExpiry", 12L)
        .containsEntry("signatureAlgorithm", "SHA256withRSA")
        .containsEntry("nearExpiry", true);
    assertThat((List<String>) result.get("sanDnsNames")).containsExactly("example.com");
    assertThat((List<String>) result.get("warnings")).containsExactly("certificate-near-expiry");
  }

  @Test
  @SuppressWarnings("unchecked")
  void reportsExpiredAndNotYetValidWarnings() throws Exception {
    X509Certificate expired =
        certificate(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), "CN=example.com");
    X509Certificate future =
        certificate(Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "CN=example.com");

    assertThat((List<String>) inspector.inspectCertificate("example.com", expired, null).get("warnings"))
        .contains("certificate-expired");
    assertThat((List<String>) inspector.inspectCertificate("example.com", future, null).get("warnings"))
        .contains("certificate-not-yet-valid");
  }

  @Test
  @SuppressWarnings("unchecked")
  void reportsHostnameMismatchWhenSessionVerifierRejectsCertificate() throws Exception {
    X509Certificate certificate =
        certificate(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "CN=other.example");
    SSLSession session = mock(SSLSession.class);
    when(session.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    when(session.getPeerHost()).thenReturn("other.example");

    Map<String, Object> result = inspector.inspectCertificate("example.com", certificate, session);

    assertThat((List<String>) result.get("warnings")).contains("certificate-hostname-mismatch");
    assertThat(result).containsEntry("hostnameMatched", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsTlsHandshakeFailureToTargetTlsFailed() {
    List<String> warnings = new ArrayList<>();

    Map<String, Object> result = inspector.tlsHandshakeFailure(new SSLHandshakeException("protocol failed"), warnings);

    assertThat(result)
        .containsEntry("status", "failed")
        .containsEntry("retryable", true)
        .containsEntry("reason", "TARGET_TLS_FAILED")
        .containsEntry("message", "TLS handshake failed.");
    assertThat(warnings).containsExactly("tls-handshake-failed");
  }

  @Test
  @SuppressWarnings("unchecked")
  void expiredHandshakeFailureKeepsTlsFailureAndAddsDiagnosticCertificateWarning() throws Exception {
    X509Certificate certificate =
        certificate(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), "CN=example.com");
    SocketHarness harness = socketHarness(certificate);
    SSLHandshakeException failure = new SSLHandshakeException("raw provider detail");
    failure.initCause(new CertificateExpiredException("expired raw detail"));
    doThrow(failure).when(harness.primarySocket()).startHandshake();

    Map<String, Object> result = harness.inspector().inspect("example.com", List.of(InetAddress.getLoopbackAddress()));

    assertThat(result)
        .containsEntry("status", "failed")
        .containsEntry("retryable", true)
        .containsEntry("reason", "TARGET_TLS_FAILED")
        .containsEntry("message", "TLS handshake failed.")
        .containsEntry("expired", true)
        .containsEntry("subject", "CN=example.com");
    assertThat((List<String>) result.get("warnings")).contains("tls-handshake-failed", "certificate-expired");
  }

  @Test
  @SuppressWarnings("unchecked")
  void notYetValidHandshakeFailureAddsDiagnosticCertificateWarning() throws Exception {
    X509Certificate certificate =
        certificate(Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "CN=example.com");
    SocketHarness harness = socketHarness(certificate);
    SSLHandshakeException failure = new SSLHandshakeException("raw provider detail");
    failure.initCause(new CertificateNotYetValidException("not valid raw detail"));
    doThrow(failure).when(harness.primarySocket()).startHandshake();

    Map<String, Object> result = harness.inspector().inspect("example.com", List.of(InetAddress.getLoopbackAddress()));

    assertThat(result).containsEntry("status", "failed").containsEntry("reason", "TARGET_TLS_FAILED");
    assertThat((List<String>) result.get("warnings")).contains("tls-handshake-failed", "certificate-not-yet-valid");
  }

  @Test
  void untrustedHandshakeFailureNeverReportsOk() throws Exception {
    SocketHarness harness = socketHarness(null);
    doThrow(new SSLHandshakeException("unable to find valid certification path"))
        .when(harness.primarySocket())
        .startHandshake();

    Map<String, Object> result = harness.inspector().inspect("example.com", List.of(InetAddress.getLoopbackAddress()));

    assertThat(result)
        .containsEntry("status", "failed")
        .containsEntry("reason", "TARGET_TLS_FAILED")
        .containsEntry("message", "TLS handshake failed.");
  }

  @Test
  @SuppressWarnings("unchecked")
  void diagnosticCaptureFailureKeepsNormalizedTlsFailure() throws Exception {
    SocketHarness harness = socketHarness(null);
    SSLHandshakeException failure = new SSLHandshakeException("raw provider detail");
    failure.initCause(new CertificateExpiredException("expired raw detail"));
    doThrow(failure).when(harness.primarySocket()).startHandshake();
    doThrow(new SSLHandshakeException("diagnostic raw detail")).when(harness.diagnosticSocket()).startHandshake();

    Map<String, Object> result = harness.inspector().inspect("example.com", List.of(InetAddress.getLoopbackAddress()));

    assertThat(result)
        .containsEntry("status", "failed")
        .containsEntry("reason", "TARGET_TLS_FAILED")
        .containsEntry("message", "TLS handshake failed.")
        .doesNotContainKey("subject");
    assertThat((List<String>) result.get("warnings")).containsExactly("tls-handshake-failed");
  }

  @Test
  @SuppressWarnings("unchecked")
  void diagnosticRuntimeFailureAddsOnlyBoundedWarning() throws Exception {
    SocketHarness harness = socketHarness(null);
    SSLHandshakeException failure = new SSLHandshakeException("raw provider detail");
    failure.initCause(new CertificateExpiredException("expired raw detail"));
    doThrow(failure).when(harness.primarySocket()).startHandshake();
    doThrow(new IllegalStateException("diagnostic secret detail"))
        .when(harness.diagnosticSocket())
        .startHandshake();

    Map<String, Object> result = harness.inspector().inspect("example.com", List.of(InetAddress.getLoopbackAddress()));

    assertThat(result)
        .containsEntry("status", "failed")
        .containsEntry("reason", "TARGET_TLS_FAILED")
        .containsEntry("message", "TLS handshake failed.");
    assertThat((List<String>) result.get("warnings"))
        .containsExactly("tls-handshake-failed", "tls-diagnostic-failed")
        .noneMatch(warning -> warning.contains("secret"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void diagnosticMetadataFailureKeepsNormalizedTlsFailure() throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getNotBefore()).thenThrow(new IllegalStateException("raw metadata detail"));
    SocketHarness harness = socketHarness(certificate);
    SSLHandshakeException failure = new SSLHandshakeException("raw provider detail");
    failure.initCause(new CertificateExpiredException("expired raw detail"));
    doThrow(failure).when(harness.primarySocket()).startHandshake();

    Map<String, Object> result = harness.inspector().inspect("example.com", List.of(InetAddress.getLoopbackAddress()));

    assertThat(result)
        .containsEntry("status", "failed")
        .containsEntry("retryable", true)
        .containsEntry("reason", "TARGET_TLS_FAILED")
        .containsEntry("message", "TLS handshake failed.")
        .doesNotContainKey("subject");
    assertThat((List<String>) result.get("warnings"))
        .containsExactly("tls-handshake-failed", "tls-diagnostic-failed");
  }

  @Test
  void setsSoTimeoutBeforePrimaryAndDiagnosticHandshakes() throws Exception {
    X509Certificate certificate =
        certificate(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), "CN=example.com");
    SocketHarness harness = socketHarness(certificate);
    SSLHandshakeException failure = new SSLHandshakeException("raw provider detail");
    failure.initCause(new CertificateExpiredException("expired raw detail"));
    doThrow(failure).when(harness.primarySocket()).startHandshake();

    harness.inspector().inspect("example.com", List.of(InetAddress.getLoopbackAddress()));

    verify(harness.primarySocket()).setSoTimeout(1234);
    verify(harness.primarySocket()).startHandshake();
    verify(harness.diagnosticSocket()).setSoTimeout(1234);
    verify(harness.diagnosticSocket()).startHandshake();
  }

  @Test
  void keepsCertificateInspectionFailuresNonRetryable() {
    List<String> warnings = new ArrayList<>();

    Map<String, Object> result =
        inspector.certificateInspectionFailure(new SSLPeerUnverifiedException("No X.509 peer certificate"), warnings);

    assertThat(result)
        .containsEntry("status", "failed")
        .containsEntry("retryable", false)
        .containsEntry("reason", "certificate_inspection_failed")
        .containsEntry("message", "Certificate inspection failed.");
    assertThat(warnings).containsExactly("certificate-parsing-failed");
  }

  private X509Certificate certificate(Instant notBefore, Instant notAfter, String subject) throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getNotBefore()).thenReturn(Date.from(notBefore));
    when(certificate.getNotAfter()).thenReturn(Date.from(notAfter));
    when(certificate.getSubjectX500Principal()).thenReturn(new javax.security.auth.x500.X500Principal(subject));
    when(certificate.getIssuerX500Principal()).thenReturn(new javax.security.auth.x500.X500Principal("CN=issuer"));
    when(certificate.getSerialNumber()).thenReturn(BigInteger.valueOf(42));
    when(certificate.getSigAlgName()).thenReturn("SHA256withRSA");
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(2, subject.substring(3))));
    return certificate;
  }

  private SocketHarness socketHarness(X509Certificate diagnosticCertificate) throws Exception {
    SSLSocket primarySocket = mock(SSLSocket.class);
    SSLSocket diagnosticSocket = mock(SSLSocket.class);
    when(primarySocket.getSSLParameters()).thenReturn(new javax.net.ssl.SSLParameters());
    when(diagnosticSocket.getSSLParameters()).thenReturn(new javax.net.ssl.SSLParameters());
    if (diagnosticCertificate != null) {
      SSLSession diagnosticSession = mock(SSLSession.class);
      when(diagnosticSession.getPeerCertificates()).thenReturn(new Certificate[] {diagnosticCertificate});
      when(diagnosticSession.getPeerHost()).thenReturn("example.com");
      when(diagnosticSocket.getSession()).thenReturn(diagnosticSession);
    }

    SSLSocketFactory primaryFactory = mock(SSLSocketFactory.class);
    SSLSocketFactory diagnosticFactory = mock(SSLSocketFactory.class);
    when(primaryFactory.createSocket()).thenReturn(primarySocket);
    when(diagnosticFactory.createSocket()).thenReturn(diagnosticSocket);

    TlsCertificateInspector inspector =
        new TlsCertificateInspector(
            Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC),
            Duration.ofMillis(1234),
            primaryFactory,
            diagnosticFactory);
    return new SocketHarness(inspector, primarySocket, diagnosticSocket);
  }

  private record SocketHarness(
      TlsCertificateInspector inspector, SSLSocket primarySocket, SSLSocket diagnosticSocket) {}
}
