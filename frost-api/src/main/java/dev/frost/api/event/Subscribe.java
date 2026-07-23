package dev.frost.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler listener for the EventBus.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

    /**
     * @return execution priority of this listener (defaults to NORMAL)
     */
    EventPriority priority() default EventPriority.NORMAL;

    /**
     * @return true to skip invocation if the event has already been cancelled
     */
    boolean ignoreCancelled() default true;
}
