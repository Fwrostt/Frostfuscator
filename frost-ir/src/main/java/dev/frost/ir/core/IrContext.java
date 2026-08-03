package dev.frost.ir.core;

import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.OperationSchema;
import dev.frost.ir.analysis.AnalysisKey;
import dev.frost.ir.analysis.MethodAnalysis;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.plugin.IrPlugin;
import dev.frost.ir.plugin.IrPluginDescriptor;
import dev.frost.ir.plugin.IrPluginRegistrar;
import dev.frost.ir.type.TypeLattice;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Explicit registry and hierarchy context. Frost-IR intentionally has no global singleton state. */
public final class IrContext {
    private final Map<OperationCode, OperationSchema> schemas;
    private final TypeLattice typeLattice;
    private final Map<String, IrPluginDescriptor> plugins;
    private final Map<AnalysisKey<?>, MethodAnalysis<?>> analyses;
    private final Map<String, Supplier<? extends MethodPass>> passFactories;

    private IrContext(Builder builder) {
        schemas = Collections.unmodifiableMap(new LinkedHashMap<>(builder.schemas));
        typeLattice = builder.typeLattice;
        plugins = Collections.unmodifiableMap(new LinkedHashMap<>(builder.plugins));
        analyses = Collections.unmodifiableMap(new LinkedHashMap<>(builder.analyses));
        passFactories = Collections.unmodifiableMap(new LinkedHashMap<>(builder.passFactories));
    }

    public static IrContext standard() {
        Builder builder = builder();
        CoreOps.schemas().forEach(builder::register);
        return builder.build();
    }

    public static Builder builder() { return new Builder(); }
    public Optional<OperationSchema> schema(OperationCode code) { return Optional.ofNullable(schemas.get(code)); }
    public Map<OperationCode, OperationSchema> schemas() { return schemas; }
    public TypeLattice typeLattice() { return typeLattice; }
    public Map<String, IrPluginDescriptor> plugins() { return plugins; }

    @SuppressWarnings("unchecked")
    public <T> Optional<MethodAnalysis<T>> analysis(AnalysisKey<T> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable((MethodAnalysis<T>) analyses.get(key));
    }

    public Optional<MethodPass> createPass(String id) {
        Objects.requireNonNull(id, "id");
        Supplier<? extends MethodPass> factory = passFactories.get(id);
        if (factory == null) return Optional.empty();
        MethodPass pass = Objects.requireNonNull(factory.get(), "pass factory returned null");
        if (!pass.id().equals(id)) throw new IllegalStateException("Registered pass " + id + " created " + pass.id());
        return Optional.of(pass);
    }

    public static final class Builder implements IrPluginRegistrar {
        private final Map<OperationCode, OperationSchema> schemas = new LinkedHashMap<>();
        private final Map<String, IrPluginDescriptor> plugins = new LinkedHashMap<>();
        private final Map<AnalysisKey<?>, MethodAnalysis<?>> analyses = new LinkedHashMap<>();
        private final Map<String, Supplier<? extends MethodPass>> passFactories = new LinkedHashMap<>();
        private TypeLattice typeLattice = TypeLattice.conservative();

        public Builder register(OperationSchema schema) {
            Objects.requireNonNull(schema, "schema");
            OperationSchema previous = schemas.putIfAbsent(schema.code(), schema);
            if (previous != null) throw new IllegalArgumentException("Operation already registered: " + schema.code());
            return this;
        }

        @Override public void registerOperation(OperationSchema schema) { register(schema); }

        @Override
        public void registerAnalysis(MethodAnalysis<?> analysis) {
            Objects.requireNonNull(analysis, "analysis");
            MethodAnalysis<?> previous = analyses.putIfAbsent(analysis.key(), analysis);
            if (previous != null) throw new IllegalArgumentException("Analysis already registered: " + analysis.key().name());
        }

        @Override
        public void registerPass(String id, Supplier<? extends MethodPass> factory) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(factory, "factory");
            if (id.isBlank() || !id.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid pass id: " + id);
            if (passFactories.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("Pass already registered: " + id);
        }

        /** Installs one plugin atomically; failed registration leaves the builder unchanged. */
        public Builder install(IrPlugin plugin) {
            Objects.requireNonNull(plugin, "plugin");
            IrPluginDescriptor descriptor = Objects.requireNonNull(plugin.descriptor(), "plugin descriptor");
            if (descriptor.apiVersion() != IrPluginDescriptor.CURRENT_API_VERSION) {
                throw new IllegalArgumentException("Plugin " + descriptor.id() + " requires API "
                        + descriptor.apiVersion() + ", runtime provides " + IrPluginDescriptor.CURRENT_API_VERSION);
            }
            if (plugins.containsKey(descriptor.id())) throw new IllegalArgumentException("Plugin already installed: " + descriptor.id());
            Map<OperationCode, OperationSchema> oldSchemas = new LinkedHashMap<>(schemas);
            Map<AnalysisKey<?>, MethodAnalysis<?>> oldAnalyses = new LinkedHashMap<>(analyses);
            Map<String, Supplier<? extends MethodPass>> oldPasses = new LinkedHashMap<>(passFactories);
            try {
                plugin.register(this);
                plugins.put(descriptor.id(), descriptor);
                return this;
            } catch (RuntimeException | Error failure) {
                schemas.clear(); schemas.putAll(oldSchemas);
                analyses.clear(); analyses.putAll(oldAnalyses);
                passFactories.clear(); passFactories.putAll(oldPasses);
                throw failure;
            }
        }

        public Builder typeLattice(TypeLattice value) {
            typeLattice = Objects.requireNonNull(value, "typeLattice");
            return this;
        }

        public IrContext build() { return new IrContext(this); }
    }
}
