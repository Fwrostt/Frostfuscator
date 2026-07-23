package dev.frost.api.event.events;

import dev.frost.api.event.Cancellable;
import org.objectweb.asm.tree.ClassNode;

/**
 * Events fired during per-class transformer execution.
 */
public abstract class ClassTransformEvent {

    private final String transformerId;
    private final ClassNode classNode;

    protected ClassTransformEvent(String transformerId, ClassNode classNode) {
        this.transformerId = transformerId;
        this.classNode = classNode;
    }

    public String transformerId() {
        return transformerId;
    }

    public ClassNode classNode() {
        return classNode;
    }

    /**
     * Fired before a transformer processes a ClassNode.
     * Cancelling this event prevents the specified transformer from modifying this class.
     */
    public static final class Pre extends ClassTransformEvent implements Cancellable {
        private boolean cancelled;

        public Pre(String transformerId, ClassNode classNode) {
            super(transformerId, classNode);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }

    /**
     * Fired after a transformer processes a ClassNode.
     */
    public static final class Post extends ClassTransformEvent {
        public Post(String transformerId, ClassNode classNode) {
            super(transformerId, classNode);
        }
    }
}
