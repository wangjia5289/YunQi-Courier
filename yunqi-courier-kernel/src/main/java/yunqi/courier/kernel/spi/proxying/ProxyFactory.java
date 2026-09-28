package yunqi.courier.kernel.spi.proxying;

import java.lang.reflect.InvocationHandler;

public interface ProxyFactory {

    <T> T createProxy(Class<T> interfaceClass, InvocationHandler invocationHandler);
}
