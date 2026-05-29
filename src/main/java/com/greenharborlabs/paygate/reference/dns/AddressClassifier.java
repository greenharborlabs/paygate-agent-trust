package com.greenharborlabs.paygate.reference.dns;

import java.net.InetAddress;
import org.springframework.stereotype.Component;

@Component
public class AddressClassifier {
  public boolean isPublicRoutable(InetAddress address) {
    return !(address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isMulticastAddress()
        || address.isSiteLocalAddress());
  }
}
