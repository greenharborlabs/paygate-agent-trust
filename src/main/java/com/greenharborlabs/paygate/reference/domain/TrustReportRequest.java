package com.greenharborlabs.paygate.reference.domain;

import java.util.Set;

public record TrustReportRequest(String domain, String normalizedDomain, Set<TrustCheck> checks) {}
