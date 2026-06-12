package com.thesisguard.openbb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenBbEquityProfileResponse(List<OpenBbEquityProfile> results) {}
