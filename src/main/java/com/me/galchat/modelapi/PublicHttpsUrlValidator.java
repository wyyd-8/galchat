package com.me.galchat.modelapi;

import com.me.galchat.exception.UserRequestException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

@Component
public class PublicHttpsUrlValidator {

    private static final int MAX_URL_LENGTH = 1000;

    private final HostResolver resolver;

    public PublicHttpsUrlValidator() {
        this(host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    public PublicHttpsUrlValidator(HostResolver resolver) {
        this.resolver = resolver;
    }

    public URI validateAndNormalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new UserRequestException("Base URL 不能为空");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new UserRequestException("Base URL 过长");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException exception) {
            throw new UserRequestException("Base URL 格式不正确");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new UserRequestException("Base URL 必须使用 HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new UserRequestException("Base URL 缺少有效域名");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new UserRequestException("Base URL 不能包含用户信息、查询参数或片段");
        }
        validatePublicHost(uri.getHost());
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "";
        } else {
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        try {
            return new URI("https", null, uri.getHost(), uri.getPort(),
                    path, null, null);
        } catch (URISyntaxException exception) {
            throw new UserRequestException("Base URL 格式不正确");
        }
    }

    private void validatePublicHost(String host) {
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw new UserRequestException("Base URL 域名无法解析");
        }
        if (addresses == null || addresses.isEmpty()
                || addresses.stream().anyMatch(address -> !isPublic(address))) {
            throw new UserRequestException("Base URL 必须解析到公网地址");
        }
    }

    private boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            boolean uniqueLocal = (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
            boolean documentation = bytes[0] == 0x20 && bytes[1] == 0x01
                    && bytes[2] == 0x0d && (bytes[3] & 0xff) == 0xb8;
            boolean unspecified = Arrays.equals(bytes, new byte[16]);
            return !uniqueLocal && !documentation && !unspecified;
        }
        return false;
    }
}
