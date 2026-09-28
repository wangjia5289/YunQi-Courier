package yunqi.courier.proxying.bytebuddy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import yunqi.courier.common.exception.CourierException;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.proxying.ProxyFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;

import static net.bytebuddy.matcher.ElementMatchers.isFinalizer;
import static net.bytebuddy.matcher.ElementMatchers.isVirtual;
import static net.bytebuddy.matcher.ElementMatchers.not;

/** Byte Buddy alternative to the JDK dynamic-proxy implementation. */
@SPI("bytebuddy")
public final class ByteBuddyProxyFactory implements ProxyFactory {

    @Override
    public <T> T createProxy(Class<T> interfaceClass, InvocationHandler invocationHandler) {
        if (interfaceClass == null || !interfaceClass.isInterface()
                || !Modifier.isPublic(interfaceClass.getModifiers())) {
            throw new IllegalArgumentException("interfaceClass must be a public interface");
        }
        if (invocationHandler == null) {
            throw new IllegalArgumentException("invocationHandler must not be null");
        }
        try {
            Class<? extends T> proxyClass = new ByteBuddy()
                    .subclass(Object.class)
                    .implement(interfaceClass)
                    .method(isVirtual().and(not(isFinalizer())))
                    .intercept(InvocationHandlerAdapter.of(invocationHandler))
                    .make()
                    .load(interfaceClass.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                    .getLoaded()
                    .asSubclass(interfaceClass);
            return proxyClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new CourierException("Create Byte Buddy proxy failed, interface=" + interfaceClass.getName(), e);
        }
    }
}
