package dev.frost.obfuscator.config.preset;

import java.util.*;

public enum ExclusionPreset {
    SPIGOT(
            "Spigot / Bukkit / Paper",
            "Excludes Spigot, Bukkit, BungeeCord, and Paper API references, event listeners, and command executors",
            List.of(
                    "org.bukkit.**",
                    "net.md_5.bungee.**",
                    "com.destroystokyo.paper.**",
                    "io.papermc.paper.**",
                    "org.spigotmc.**",
                    "co.aikar.commands.**"
            ),
            List.of(
                    "org/bukkit/event/Listener",
                    "org/bukkit/command/CommandExecutor",
                    "org/bukkit/command/TabCompleter",
                    "org/bukkit/plugin/Plugin",
                    "org/bukkit/event/Event"
            ),
            List.of(
                    "Lorg/bukkit/event/EventHandler;"
            )
    ),
    FABRIC(
            "Fabric Loader & Mixins",
            "Excludes Fabric API, ModInitializers, entrypoints, and SpongePowered Mixin annotations",
            List.of(
                    "net.fabricmc.**",
                    "org.spongepowered.asm.**"
            ),
            List.of(
                    "net/fabricmc/api/ModInitializer",
                    "net/fabricmc/api/ClientModInitializer",
                    "net/fabricmc/api/DedicatedServerModInitializer",
                    "net/fabricmc/api/EnvType",
                    "net/fabricmc/api/Environment"
            ),
            List.of(
                    "Lorg/spongepowered/asm/mixin/Mixin;",
                    "Lorg/spongepowered/asm/mixin/injection/Inject;",
                    "Lorg/spongepowered/asm/mixin/injection/Redirect;",
                    "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
                    "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
                    "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
                    "Lorg/spongepowered/asm/mixin/injection/At;",
                    "Lorg/spongepowered/asm/mixin/Shadow;",
                    "Lorg/spongepowered/asm/mixin/Overwrite;",
                    "Lorg/spongepowered/asm/mixin/gen/Accessor;",
                    "Lorg/spongepowered/asm/mixin/gen/Invoker;"
            )
    ),
    FORGE(
            "Minecraft Forge & NeoForge",
            "Excludes Forge / NeoForge API classes, mod entrypoints, and event subscribers",
            List.of(
                    "net.minecraftforge.**",
                    "net.neoforged.**",
                    "cpw.mods.fml.**"
            ),
            List.of(),
            List.of(
                    "Lnet/minecraftforge/fml/common/Mod;",
                    "Lnet/minecraftforge/eventbus/api/SubscribeEvent;",
                    "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;",
                    "Lnet/minecraftforge/api/distmarker/OnlyIn;",
                    "Lnet/neoforged/bus/api/SubscribeEvent;",
                    "Lnet/neoforged/fml/common/Mod;"
            )
    ),
    GSON(
            "Google Gson",
            "Excludes Gson library classes and @SerializedName / @Expose annotated fields",
            List.of(
                    "com.google.gson.**"
            ),
            List.of(
                    "com/google/gson/JsonSerializer",
                    "com/google/gson/JsonDeserializer",
                    "com/google/gson/TypeAdapterFactory"
            ),
            List.of(
                    "Lcom/google/gson/annotations/SerializedName;",
                    "Lcom/google/gson/annotations/Expose;"
            )
    ),
    JACKSON(
            "Jackson JSON",
            "Excludes Jackson library classes and JSON binding annotations",
            List.of(
                    "com.fasterxml.jackson.**"
            ),
            List.of(),
            List.of(
                    "Lcom/fasterxml/jackson/annotation/JsonProperty;",
                    "Lcom/fasterxml/jackson/annotation/JsonIgnore;",
                    "Lcom/fasterxml/jackson/annotation/JsonTypeInfo;",
                    "Lcom/fasterxml/jackson/annotation/JsonSubTypes;",
                    "Lcom/fasterxml/jackson/annotation/JsonAutoDetect;",
                    "Lcom/fasterxml/jackson/annotation/JsonCreator;",
                    "Lcom/fasterxml/jackson/databind/annotation/JsonDeserialize;",
                    "Lcom/fasterxml/jackson/databind/annotation/JsonSerialize;"
            )
    ),
    SPRING(
            "Spring Framework",
            "Excludes Spring core/web classes and spring component/autowire annotations",
            List.of(
                    "org.springframework.**"
            ),
            List.of(),
            List.of(
                    "Lorg/springframework/stereotype/Component;",
                    "Lorg/springframework/stereotype/Service;",
                    "Lorg/springframework/stereotype/Repository;",
                    "Lorg/springframework/stereotype/Controller;",
                    "Lorg/springframework/web/bind/annotation/RestController;",
                    "Lorg/springframework/beans/factory/annotation/Autowired;",
                    "Lorg/springframework/context/annotation/Bean;",
                    "Lorg/springframework/context/annotation/Configuration;",
                    "Lorg/springframework/beans/factory/annotation/Value;",
                    "Lorg/springframework/web/bind/annotation/RequestMapping;",
                    "Lorg/springframework/web/bind/annotation/GetMapping;",
                    "Lorg/springframework/web/bind/annotation/PostMapping;"
            )
    ),
    JPA(
            "JPA / Hibernate",
            "Excludes Jakarta/JPA persistence annotations and entity mappings",
            List.of(
                    "jakarta.persistence.**",
                    "javax.persistence.**",
                    "org.hibernate.**"
            ),
            List.of(),
            List.of(
                    "Ljakarta/persistence/Entity;",
                    "Ljakarta/persistence/Table;",
                    "Ljakarta/persistence/Column;",
                    "Ljakarta/persistence/Id;",
                    "Ljakarta/persistence/GeneratedValue;",
                    "Ljavax/persistence/Entity;",
                    "Ljavax/persistence/Table;",
                    "Ljavax/persistence/Column;",
                    "Ljavax/persistence/Id;"
            )
    ),
    SPONGE(
            "Sponge API",
            "Excludes SpongePowered API classes, @Plugin, and @Listener annotated handlers",
            List.of(
                    "org.spongepowered.api.**"
            ),
            List.of(),
            List.of(
                    "Lorg/spongepowered/api/plugin/Plugin;",
                    "Lorg/spongepowered/api/event/Listener;"
            )
    );

    private final String displayName;
    private final String description;
    private final List<String> packageExclusions;
    private final List<String> interfaceExclusions;
    private final List<String> annotationExclusions;

    ExclusionPreset(String displayName, String description, List<String> packageExclusions,
                    List<String> interfaceExclusions, List<String> annotationExclusions) {
        this.displayName = displayName;
        this.description = description;
        this.packageExclusions = packageExclusions;
        this.interfaceExclusions = interfaceExclusions;
        this.annotationExclusions = annotationExclusions;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getPackageExclusions() {
        return packageExclusions;
    }

    public List<String> getInterfaceExclusions() {
        return interfaceExclusions;
    }

    public List<String> getAnnotationExclusions() {
        return annotationExclusions;
    }

    public static ExclusionPreset parse(String input) {
        if (input == null || input.isBlank()) return null;
        String clean = input.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ExclusionPreset preset : values()) {
            if (preset.name().equals(clean)) {
                return preset;
            }
        }
        return null;
    }
}
