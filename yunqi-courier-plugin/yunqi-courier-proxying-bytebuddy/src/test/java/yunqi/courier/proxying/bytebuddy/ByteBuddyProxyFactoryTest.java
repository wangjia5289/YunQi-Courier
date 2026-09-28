package yunqi.courier.proxying.bytebuddy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ByteBuddyProxyFactoryTest {

    @Test
    void shouldDelegateInterfaceCallsToInvocationHandler() {
        ByteBuddyProxyFactory factory = new ByteBuddyProxyFactory();
        Greeting proxy = factory.createProxy(Greeting.class,
                (ignored, method, arguments) -> method.getName().equals("hello") ? "hello " + arguments[0] : null);

        assertEquals("hello courier", proxy.hello("courier"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createProxy(String.class, (ignored, method, arguments) -> null));
    }

    public interface Greeting {
        String hello(String name);
    }
}
