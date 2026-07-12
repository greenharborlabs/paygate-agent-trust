package com.greenharborlabs.paygate.reference.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DnsVettingServiceTest {
  @Test
  void resolvesPublicDomain() throws Exception {
    var service =
        new DnsVettingService(
            new AddressClassifier(), domain -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
    assertThat(service.resolvePublic("example.com"))
        .extracting(InetAddress::getHostAddress)
        .containsExactly("93.184.216.34");
  }

  @Test
  void rejectsPrivateOnlyDomain() throws Exception {
    var service =
        new DnsVettingService(
            new AddressClassifier(), domain -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
    assertThatThrownBy(() -> service.resolvePublic("localhost"))
        .isInstanceOf(ApiProblem.class)
        .satisfies(ex -> assertThat(((ApiProblem) ex).code()).isEqualTo("UNSAFE_TARGET"));
  }

  @Test
  void rejectsMixedSafeAndUnsafeAnswers() throws Exception {
    var service =
        new DnsVettingService(
            new AddressClassifier(),
            domain ->
                new InetAddress[] {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")});
    assertThatThrownBy(() -> service.resolvePublic("example.com"))
        .isInstanceOf(ApiProblem.class)
        .satisfies(ex -> assertThat(((ApiProblem) ex).code()).isEqualTo("UNSAFE_TARGET"));
  }

  @Test
  void mapsLookupFailure() {
    var lookupFailure = new UnknownHostException("boom");
    var service =
        new DnsVettingService(
            new AddressClassifier(),
            domain -> {
              throw lookupFailure;
            });
    assertThatThrownBy(() -> service.resolvePublic("missing.example"))
        .isInstanceOf(ApiProblem.class)
        .satisfies(
            ex -> {
              assertThat(((ApiProblem) ex).code()).isEqualTo("DNS_LOOKUP_FAILED");
              assertThat(ex.getCause()).isSameAs(lookupFailure);
            });
  }

  @Test
  void rejectsEmptyDnsAnswer() {
    var service = new DnsVettingService(new AddressClassifier(), domain -> new InetAddress[0]);
    assertThatThrownBy(() -> service.resolvePublic("empty.example"))
        .isInstanceOf(ApiProblem.class)
        .satisfies(ex -> assertThat(((ApiProblem) ex).code()).isEqualTo("DNS_LOOKUP_FAILED"));
  }
}
