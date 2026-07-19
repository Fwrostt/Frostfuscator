package com.acme.frostfixture;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

public final class FixtureApp {
    private final ServiceApi service = new ServiceImpl();

    public static void main(String[] args) {
        System.out.println(new FixtureApp().run(args));
    }

    public String run(String[] args) {
        int seed = args == null ? 0 : args.length;
        String message = StringsAndNumbers.message(seed);
        int score = FlowCases.score(seed, message);
        List<String> values = Arrays.asList(message, service.compute("alpha", score), LambdaUser.join("beta", score));
        TracePrinter.capture(new IllegalArgumentException("fixture trace"));
        ReflectionTarget target = new ReflectionTarget("fixture");
        return values + ":" + target.publicName() + ":" + ResourceUser.readConfigMarker();
    }

    public int inlineCandidate() {
        return tinyAnswer() + StringsAndNumbers.numericMixer(7);
    }

    private static int tinyAnswer() {
        return 42;
    }

    private static String unreachablePrivateMethod() {
        return "dead-code-target";
    }

    public Callable<String> callable() {
        return () -> service.compute("callable", tinyAnswer());
    }
}
