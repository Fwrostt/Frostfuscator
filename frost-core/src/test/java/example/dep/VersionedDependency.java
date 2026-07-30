package example.dep;

public final class VersionedDependency {
    private VersionedDependency() { }

    public static String version() {
        return "host";
    }
}
