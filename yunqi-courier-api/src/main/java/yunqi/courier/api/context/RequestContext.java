package yunqi.courier.api.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RequestContext {

    private static final ThreadLocal<Map<String, String>> ATTACHMENTS = ThreadLocal.withInitial(HashMap::new);

    private static final ThreadLocal<Object> PRINCIPAL = new ThreadLocal<>();

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    public static void putAttachment(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("attachment key must not be blank");
        }
        if (value == null) {
            ATTACHMENTS.get().remove(key);
            return;
        }
        ATTACHMENTS.get().put(key, value);
    }

    public static String getAttachment(String key) {
        return ATTACHMENTS.get().get(key);
    }

    public static Map<String, String> getAttachments() {
        return Collections.unmodifiableMap(new HashMap<>(ATTACHMENTS.get()));
    }

    public static void clear() {
        ATTACHMENTS.remove();
        PRINCIPAL.remove();
        TRACE_ID.remove();
    }

    public static void setAttachments(Map<String, String> attachments) {
        Map<String, String> current = ATTACHMENTS.get();
        current.clear();
        if (attachments != null) {
            current.putAll(attachments);
        }
    }

    public static Object getPrincipal() {
        return PRINCIPAL.get();
    }

    public static void setPrincipal(Object principal) {
        PRINCIPAL.set(principal);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setTraceId(String traceId) {
        if (traceId == null) {
            TRACE_ID.remove();
        } else {
            TRACE_ID.set(traceId);
        }
    }
}
