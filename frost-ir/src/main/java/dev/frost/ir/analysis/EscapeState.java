package dev.frost.ir.analysis;

/** Increasing escape lattice; only NO_ESCAPE authorizes scalar-replacement style assumptions. */
public enum EscapeState {
    NO_ESCAPE,
    ARGUMENT_ESCAPE,
    METHOD_ESCAPE,
    GLOBAL_ESCAPE;

    public EscapeState merge(EscapeState other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
