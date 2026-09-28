package yunqi.courier.kernel.spi.core;

import yunqi.courier.common.exception.CourierException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Modifier;

public final class ServiceProviderLoader {

    private static final String DIRECTORY = "META-INF/yunqi-courier/plugin/";

    private static final Map<ProviderCacheKey, Map<String, Class<?>>> PROVIDER_CLASS_CACHE = new ConcurrentHashMap<>();

    private static final Map<ProviderInstanceCacheKey, Object> PROVIDER_INSTANCE_CACHE = new ConcurrentHashMap<>();

    private ServiceProviderLoader() {
    }

    public static <T> T load(Class<T> serviceClass, String name) {
        ClassLoader classLoader = resolveClassLoader(serviceClass);
        Class<?> providerClass = findProviderClass(serviceClass, name, classLoader);
        ProviderInstanceCacheKey cacheKey = new ProviderInstanceCacheKey(serviceClass, name, classLoader);
        Object provider = PROVIDER_INSTANCE_CACHE.computeIfAbsent(cacheKey, key -> newProvider(providerClass));
        return serviceClass.cast(provider);
    }

    public static <T> T newInstance(Class<T> serviceClass, String name) {
        ClassLoader classLoader = resolveClassLoader(serviceClass);
        return serviceClass.cast(newProvider(findProviderClass(serviceClass, name, classLoader)));
    }

    private static Class<?> findProviderClass(Class<?> serviceClass, String name, ClassLoader classLoader) {
        Objects.requireNonNull(serviceClass, "serviceClass");
        Objects.requireNonNull(name, "name");
        ProviderCacheKey cacheKey = new ProviderCacheKey(serviceClass, classLoader);
        Map<String, Class<?>> providerClasses = PROVIDER_CLASS_CACHE.computeIfAbsent(cacheKey,
                key -> loadProviderClasses(serviceClass, classLoader));
        Class<?> providerClass = providerClasses.get(name);
        if (providerClass == null) {
            throw new CourierException("Can not find provider, service=" + serviceClass.getName() + ", name=" + name);
        }
        return providerClass;
    }

    private static Map<String, Class<?>> loadProviderClasses(Class<?> serviceClass, ClassLoader classLoader) {
        Map<String, Class<?>> providerClasses = new ConcurrentHashMap<>();
        String resourceName = DIRECTORY + serviceClass.getName();
        for (ClassLoader loader : candidateClassLoaders(serviceClass, classLoader)) {
            try {
                Enumeration<URL> resources = loader.getResources(resourceName);
                while (resources.hasMoreElements()) {
                    URL resource = resources.nextElement();
                    loadResource(serviceClass, loader, resource, providerClasses);
                }
            } catch (IOException e) {
                throw new CourierException("Load provider resource failed, service=" + serviceClass.getName(), e);
            }
        }
        return providerClasses;
    }

    private static List<ClassLoader> candidateClassLoaders(Class<?> serviceClass, ClassLoader preferred) {
        List<ClassLoader> loaders = new ArrayList<>();
        addLoader(loaders, preferred);
        addLoader(loaders, serviceClass.getClassLoader());
        addLoader(loaders, ServiceProviderLoader.class.getClassLoader());
        addLoader(loaders, ClassLoader.getSystemClassLoader());
        return loaders;
    }

    private static void addLoader(List<ClassLoader> loaders, ClassLoader loader) {
        if (loader != null && !loaders.contains(loader)) {
            loaders.add(loader);
        }
    }

    private static void loadResource(Class<?> serviceClass, ClassLoader classLoader, URL resource,
                                     Map<String, Class<?>> providerClasses) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String className = line.trim();
                if (className.isEmpty() || className.startsWith("#")) {
                    continue;
                }
                Class<?> providerClass = Class.forName(className, true, classLoader);
                if (!serviceClass.isAssignableFrom(providerClass)) {
                    throw new CourierException(providerClass.getName() + " is not subtype of " + serviceClass.getName());
                }
                SPI spi = providerClass.getAnnotation(SPI.class);
                if (spi == null) {
                    throw new CourierException(providerClass.getName() + " missing @SPI");
                }
                Class<?> previous = providerClasses.putIfAbsent(spi.value(), providerClass);
                if (previous != null && previous != providerClass) {
                    throw new CourierException("Duplicate provider name, service="
                            + serviceClass.getName() + ", name=" + spi.value()
                            + ", providers=" + previous.getName() + "," + providerClass.getName());
                }
            }
        } catch (Exception e) {
            throw new CourierException("Load provider class failed, resource=" + resource, e);
        }
    }

    private static Object newProvider(Class<?> providerClass) {
        try {
            var constructor = providerClass.getDeclaredConstructor();
            if (!Modifier.isPublic(constructor.getModifiers()) || !Modifier.isPublic(providerClass.getModifiers())) {
                throw new IllegalArgumentException("Provider must have a public no-argument constructor: "
                        + providerClass.getName());
            }
            return constructor.newInstance();
        } catch (Exception e) {
            throw new CourierException("Create provider failed, class=" + providerClass.getName(), e);
        }
    }

    private static ClassLoader resolveClassLoader(Class<?> serviceClass) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            return contextClassLoader;
        }
        ClassLoader serviceClassLoader = serviceClass.getClassLoader();
        if (serviceClassLoader != null) {
            return serviceClassLoader;
        }
        ClassLoader loader = ServiceProviderLoader.class.getClassLoader();
        return loader == null ? ClassLoader.getSystemClassLoader() : loader;
    }

    private record ProviderCacheKey(Class<?> serviceClass, ClassLoader classLoader) {
    }

    private record ProviderInstanceCacheKey(Class<?> serviceClass, String name, ClassLoader classLoader) {
    }
}
