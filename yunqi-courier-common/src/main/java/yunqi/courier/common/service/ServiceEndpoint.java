package yunqi.courier.common.service;

import java.io.Serializable;
import java.util.Objects;

public class ServiceEndpoint implements Serializable {

    private String host;

    private int port;

    public ServiceEndpoint() {
    }

    public ServiceEndpoint(String host, int port) {
        this.host = requireHost(host);
        this.port = requirePort(port);
    }

    public String address() {
        if (!isValid()) {
            throw new IllegalStateException("Endpoint host and port must be valid");
        }
        String displayHost = host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))
                ? "[" + host + "]" : host;
        return displayHost + ":" + port;
    }

    /** Returns whether this endpoint has a non-blank host and a valid TCP port. */
    public boolean isValid() {
        return host != null && !host.isBlank() && port >= 1 && port <= 65_535;
    }

    public static ServiceEndpoint parse(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalArgumentException("Invalid endpoint address: " + address);
        }
        String host = address.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port;
        try {
            port = Integer.parseInt(address.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid endpoint port: " + address, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Endpoint port must be between 1 and 65535: " + address);
        }
        return new ServiceEndpoint(host, port);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = requireHost(host);
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = requirePort(port);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceEndpoint that)) {
            return false;
        }
        return port == that.port && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return address();
    }

    private static String requireHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Endpoint host must not be blank");
        }
        if (!host.equals(host.trim())) {
            throw new IllegalArgumentException("Endpoint host must not have surrounding whitespace");
        }
        if (host.startsWith("[") || host.endsWith("]")) {
            if (!(host.startsWith("[") && host.endsWith("]"))) {
                throw new IllegalArgumentException("Endpoint host has unmatched brackets");
            }
            host = host.substring(1, host.length() - 1);
            if (host.isBlank()) {
                throw new IllegalArgumentException("Endpoint host must not be blank");
            }
        }
        if (host.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Endpoint host must not contain control characters");
        }
        return host;
    }

    private static int requirePort(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Endpoint port must be between 1 and 65535");
        }
        return port;
    }
}
