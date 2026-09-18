package top.xiamoi.moocpass.infrastructure;

import okhttp3.*;
import org.springframework.stereotype.Component;
import top.xiamoi.moocpass.common.ApiException;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;

@Component
public class OutboundHttp {
    private final OkHttpClient client;

    public OutboundHttp() {
        this(null);
    }

    public OutboundHttp(top.xiamoi.moocpass.config.MoocProperties properties) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15)).readTimeout(Duration.ofSeconds(60))
            .callTimeout(Duration.ofSeconds(75)).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .dns(host -> {
                List<InetAddress> addresses = Dns.SYSTEM.lookup(host);
                if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address)))
                    throw new UnknownHostException("Outbound address is not public");
                return addresses;
            });
        if (properties != null && properties.getProxy() != null && properties.getProxy().isApplyToOutbound()) {
            var p = properties.getProxy();
            top.xiamoi.moocpass.util.OkHttpUtil.applyProxy(builder, new top.xiamoi.moocpass.util.OkHttpUtil.ProxyConfig(
                p.isEnabled(), p.getType(), p.getHost(), p.getPort(), p.getUsername(), p.getPassword()
            ));
        }
        this.client = builder.build();
    }

    public HttpUrl validate(String value) {
        HttpUrl url;
        try { url = HttpUrl.get(value); } catch (Exception error) { throw ApiException.invalid("接口地址格式无效"); }
        if (!url.isHttps() || url.port() != 443 || !url.username().isEmpty() || !url.password().isEmpty()
            || url.fragment() != null || url.host().equalsIgnoreCase("localhost")
            || url.host().endsWith(".local") || !url.host().contains("."))
            throw ApiException.invalid("接口需使用公共 HTTPS 域名和 443 端口");
        try {
            for (InetAddress address : Dns.SYSTEM.lookup(url.host())) {
                if (!isPublic(address)) throw ApiException.invalid("不能访问该接口地址");
            }
        } catch (UnknownHostException error) { throw ApiException.invalid("接口域名无法解析"); }
        return url;
    }
    public Response execute(Request request) throws IOException {
        validate(request.url().toString());
        return client.newCall(request).execute();
    }
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]);
            return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127) && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && (b == 168 || b == 0));
        }
        return bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) & 0xe0) == 0x20
            && !(bytes[0] == 0x20 && bytes[1] == 0x02);
    }
}
