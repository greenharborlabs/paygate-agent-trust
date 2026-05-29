package com.greenharborlabs.paygate.reference.dns;

import com.greenharborlabs.paygate.reference.api.ApiProblem;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DnsVettingService {
  @FunctionalInterface
  public interface DnsLookup {
    InetAddress[] lookup(String domain) throws UnknownHostException;
  }

  private final AddressClassifier classifier;
  private final DnsLookup dnsLookup;

  @Autowired
  public DnsVettingService(AddressClassifier classifier) {
    this(classifier, InetAddress::getAllByName);
  }

  public DnsVettingService(AddressClassifier classifier, DnsLookup dnsLookup) {
    this.classifier = classifier;
    this.dnsLookup = dnsLookup;
  }

  public List<InetAddress> resolvePublic(String domain) {
    try {
      List<InetAddress> addresses = Arrays.asList(dnsLookup.lookup(domain));
      if (addresses.isEmpty()) {
        throw new ApiProblem("DNS_LOOKUP_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, true, "No DNS answer.");
      }
      boolean allPublic = addresses.stream().allMatch(classifier::isPublicRoutable);
      if (!allPublic) {
        throw new ApiProblem("UNSAFE_TARGET", HttpStatus.BAD_REQUEST, false, "The resolved address is not publicly routable.");
      }
      return addresses;
    } catch (UnknownHostException ex) {
      throw new ApiProblem("DNS_LOOKUP_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, true, "DNS lookup failed.");
    }
  }
}
