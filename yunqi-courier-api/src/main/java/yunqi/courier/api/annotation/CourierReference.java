package yunqi.courier.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CourierReference {

    Class<?> interfaceClass() default void.class;

    String group() default "default";

    String version() default "1.0.0";

    int timeoutMillis() default -1;
}
