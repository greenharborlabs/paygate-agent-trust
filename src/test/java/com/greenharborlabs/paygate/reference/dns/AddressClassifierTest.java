package com.greenharborlabs.paygate.reference.dns;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class AddressClassifierTest {
  private final AddressClassifier classifier = new AddressClassifier();

  @Test
  void identifiesPrivateAddress() throws Exception {
    assertThat(classifier.isPublicRoutable(InetAddress.getByName("10.0.0.1"))).isFalse();
    assertThat(classifier.isPublicRoutable(InetAddress.getByName("1.1.1.1"))).isTrue();
  }
}
