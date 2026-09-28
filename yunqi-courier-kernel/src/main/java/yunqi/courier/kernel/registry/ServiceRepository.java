package yunqi.courier.kernel.registry;

import yunqi.courier.common.exception.CourierException;
import yunqi.courier.common.exception.RequestValidationException;
import yunqi.courier.common.exception.ServiceNotFoundException;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierRequestValidator;
import yunqi.courier.common.service.ServiceMetadata;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class ServiceRepository {

    private final Map<String, Object> serviceInstanceMap = new ConcurrentHashMap<>();

    private final Map<String, ServiceMetadata> serviceMetadataMap = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> exportedMethodMap = new ConcurrentHashMap<>();

    public void validateExport(ServiceMetadata serviceMetadata, Object serviceImpl) {
        validateExportAndResolveInterface(serviceMetadata, serviceImpl);
    }

    public void export(ServiceMetadata serviceMetadata, Object serviceImpl) {
        Class<?> serviceInterface = validateExportAndResolveInterface(serviceMetadata, serviceImpl);
        Set<String> methods = new HashSet<>();
        for (Method method : serviceInterface.getMethods()) {
            if (isExportableMethod(method)) {
                methods.add(methodKey(method.getName(), method.getParameterTypes()));
            }
        }
        serviceInstanceMap.put(serviceMetadata.serviceKey(), serviceImpl);
        serviceMetadataMap.put(serviceMetadata.serviceKey(), serviceMetadata);
        exportedMethodMap.put(serviceMetadata.serviceKey(), Set.copyOf(methods));
    }

    public Object invoke(CourierRequest request) {
        CourierRequestValidator.validate(request);
        Object serviceImpl = serviceInstanceMap.get(request.getServiceName());
        if (serviceImpl == null) {
            throw new ServiceNotFoundException("Service not exported, service=" + request.getServiceName());
        }
        try {
            String[] parameterTypeNames = request.getParameterTypeNames();
            Object[] parameters = request.getParameters();
            if (parameterTypeNames == null || parameters == null
                    || parameterTypeNames.length != parameters.length) {
                throw new RequestValidationException("Request parameter metadata does not match parameter values");
            }
            Set<String> exportedMethods = exportedMethodMap.get(request.getServiceName());
            if (exportedMethods == null || !exportedMethods.contains(methodKey(request.getMethodName(), parameterTypeNames))) {
                throw new RequestValidationException("Method is not exported, service=" + request.getServiceName()
                        + ", method=" + request.getMethodName());
            }
            Class<?>[] parameterTypes = resolveParameterTypes(parameterTypeNames, serviceImpl.getClass().getClassLoader());
            ServiceMetadata metadata = serviceMetadataMap.get(request.getServiceName());
            Class<?> serviceInterface = metadata == null ? null : metadata.getServiceInterface();
            if (serviceInterface == null) {
                throw new RequestValidationException("Exported service interface is unavailable, service="
                        + request.getServiceName());
            }
            Method method = serviceInterface.getMethod(request.getMethodName(), parameterTypes);
            if (!isExportableMethod(method)) {
                throw new RequestValidationException("Method is not an exported instance RPC method, service="
                        + request.getServiceName() + ", method=" + request.getMethodName());
            }
            // Public interface methods are invocable without breaking module boundaries.
            // Package-private test/application interfaces may still need an accessibility
            // fallback, but trySetAccessible() lets strongly encapsulated modules reject it.
            if (!method.canAccess(serviceImpl) && !method.trySetAccessible()) {
                throw new IllegalAccessException("Service method is not accessible: " + method);
            }
            return method.invoke(serviceImpl, parameters);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CourierException("Invoke service failed, request=" + request, targetException);
        } catch (RequestValidationException e) {
            throw e;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalArgumentException e) {
            throw new RequestValidationException("Invalid service method signature, request=" + request);
        } catch (Exception e) {
            throw new CourierException("Invoke service failed, request=" + request, e);
        }
    }

    public Map<String, ServiceMetadata> getServiceMetadataMap() {
        Map<String, ServiceMetadata> snapshots = new ConcurrentHashMap<>();
        serviceMetadataMap.forEach((key, value) -> snapshots.put(key, value.snapshot()));
        return Collections.unmodifiableMap(snapshots);
    }

    private Class<?> validateExportAndResolveInterface(ServiceMetadata serviceMetadata, Object serviceImpl) {
        if (serviceMetadata == null) {
            throw new IllegalArgumentException("serviceMetadata must not be null");
        }
        serviceMetadata.validate();
        if (serviceImpl == null) {
            throw new IllegalArgumentException("serviceImpl must not be null");
        }
        Class<?> serviceInterface = serviceMetadata.getServiceInterface();
        if (serviceInterface == null) {
            Class<?>[] interfaces = serviceImpl.getClass().getInterfaces();
            serviceInterface = interfaces.length == 0 ? null : interfaces[0];
        }
        if (serviceInterface == null || !serviceInterface.isInterface()
                || !serviceInterface.isInstance(serviceImpl)) {
            throw new IllegalArgumentException("An exported service must implement the declared interface");
        }
        serviceMetadata.setServiceInterface(serviceInterface);
        return serviceInterface;
    }

    private Class<?>[] resolveParameterTypes(String[] parameterTypeNames, ClassLoader classLoader) throws ClassNotFoundException {
        if (parameterTypeNames == null || parameterTypeNames.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] parameterTypes = new Class<?>[parameterTypeNames.length];
        if (classLoader == null) {
            classLoader = ServiceRepository.class.getClassLoader();
        }
        for (int i = 0; i < parameterTypeNames.length; i++) {
            parameterTypes[i] = primitiveType(parameterTypeNames[i], classLoader);
        }
        return parameterTypes;
    }

    private Class<?> primitiveType(String typeName, ClassLoader classLoader) throws ClassNotFoundException {
        return switch (typeName) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            case "void" -> void.class;
            default -> Class.forName(typeName, false, classLoader);
        };
    }

    private String methodKey(String methodName, Class<?>[] parameterTypes) {
        return methodName + Arrays.stream(parameterTypes).map(Class::getName).reduce("", (left, right) -> left + "#" + right);
    }

    private String methodKey(String methodName, String[] parameterTypeNames) {
        return methodName + Arrays.stream(parameterTypeNames).reduce("", (left, right) -> left + "#" + right);
    }

    private boolean isExportableMethod(Method method) {
        int modifiers = method.getModifiers();
        return !java.lang.reflect.Modifier.isStatic(modifiers)
                && !method.isSynthetic()
                && !method.isBridge();
    }

}
