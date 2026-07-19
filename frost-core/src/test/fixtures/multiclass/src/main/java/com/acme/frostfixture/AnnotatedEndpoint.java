package com.acme.frostfixture;

@FixtureMarker("endpoint")
public final class AnnotatedEndpoint {
    @FixtureMarker("method")
    public String handle(String request) {
        return "handled:" + request;
    }
}
