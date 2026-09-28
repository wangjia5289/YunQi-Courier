package yunqi.courier.proxying.jdk;

import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.proxying.ProxyFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

@SPI("jdk")
public class JdkProxyFactory implements ProxyFactory {

    @Override
    public <T> T createProxy(Class<T> interfaceClass, InvocationHandler invocationHandler) {
        Object proxy = Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                invocationHandler
        );
        return interfaceClass.cast(proxy);
    }
}
