package yunqi.courier.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CourierProvider {

    Class<?> interfaceClass();

    String group() default "default";

    String version() default "1.0.0";
}
