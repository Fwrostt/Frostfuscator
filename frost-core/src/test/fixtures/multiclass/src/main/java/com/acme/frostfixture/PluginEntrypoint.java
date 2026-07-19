package com.acme.frostfixture;

public final class PluginEntrypoint {
    public void onLoad() {
        StringsAndNumbers.message(1);
    }

    public void onEnable() {
        new FixtureApp().run(new String[]{"plugin"});
    }
}
