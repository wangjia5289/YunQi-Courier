package yunqi.courier.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an RPC operation as safe to invoke more than once after a transport
 * failure. The annotation is intentionally opt-in because a timeout can occur
 * after the provider has already completed the operation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CourierIdempotent {
}
