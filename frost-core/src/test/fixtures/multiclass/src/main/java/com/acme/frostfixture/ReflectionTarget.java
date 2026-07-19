package com.acme.frostfixture;

public final class ReflectionTarget {
    public static final long serialVersionUID = 1L;
    private final String name;
    private String reflectedField = "reflect-me";

    public ReflectionTarget(String name) {
        this.name = name;
    }

    public String publicName() {
        return name + ":" + reflectedField;
    }

    private String hiddenMethod() {
        return "hidden:" + name;
    }
}
