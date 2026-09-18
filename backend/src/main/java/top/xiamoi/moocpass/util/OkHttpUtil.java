package top.xiamoi.moocpass.util;

import okhttp3.*;
import top.xiamoi.moocpass.task.ExecutionScope;
import top.xiamoi.moocpass.infrastructure.OutboundHttp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.*;

public final class OkHttpUtil {
    private static volatile ProxyConfig proxyConfig = null;

    public record ProxyConfig(
        boolean enabled,
        String type,
        String host,
        int port,
        String username,
        String password
    ) {}

    private OkHttpUtil() {}

    public static void setProxyConfig(ProxyConfig config) {
        proxyConfig = config;
    }

    public static ProxyConfig getProxyConfig() {
        return proxyConfig;
    }

    public static OkHttpClient.Builder createClientBuilder() {
        return createClientBuilder(proxyConfig);
    }

    public static OkHttpClient.Builder createClientBuilder(ProxyConfig proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20)).readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(60)).retryOnConnectionFailure(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(chain -> {
                ExecutionScope.check();
                String host=chain.request().url().host();
                boolean allowed=List.of("chaoxing.com","chaoxing.net","icve.com.cn").stream()
                    .anyMatch(domain -> host.equals(domain)||host.endsWith("."+domain));
                if(!allowed||!chain.request().url().isHttps()||chain.request().url().port()!=443)
                    throw new IOException("Platform request destination is not allowed");
                return chain.proceed(chain.request());
            })
            .addInterceptor(chain -> {
                Request request=chain.request();
                int attempts="GET".equals(request.method())?3:1;
                for(int i=0;i<attempts;i++) {
                    ExecutionScope.check();
                    try {
                        Response response=chain.proceed(request);
                        if(response.code()<500||i==attempts-1) return response;
                        response.close();
                    } catch(IOException error) {
                        if(i==attempts-1) throw error;
                    }
                    ExecutionScope.sleep((i+1)*1000L);
                }
                throw new IOException("Platform request failed");
            });

        applyProxy(builder, proxy);
        return builder;
    }

    public static void applyProxy(OkHttpClient.Builder builder, ProxyConfig config) {
        if (config != null && config.enabled() && config.host() != null && !config.host().isBlank() && config.port() > 0) {
            Proxy.Type type = "socks".equalsIgnoreCase(config.type()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            Proxy proxy = new Proxy(type, new InetSocketAddress(config.host().trim(), config.port()));
            builder.proxy(proxy);
            if (config.username() != null && !config.username().isBlank()) {
                builder.proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) {
                        return null; // Give up, prevent loop if credentials wrong
                    }
                    String credential = Credentials.basic(config.username().trim(), config.password() != null ? config.password().trim() : "");
                    return response.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
                });
            }
        }
    }

    public static CookieJar createInMemoryCookieJar() {
        return new CookieJar() {
            private final List<Cookie> cookies=new ArrayList<>();
            @Override public synchronized void saveFromResponse(HttpUrl url,List<Cookie> incoming) {
                for(Cookie value:incoming) {
                    cookies.removeIf(old -> old.name().equals(value.name())&&old.domain().equals(value.domain())&&old.path().equals(value.path()));
                    if(value.expiresAt()>System.currentTimeMillis()) cookies.add(value);
                }
            }
            @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
                cookies.removeIf(cookie -> cookie.expiresAt()<=System.currentTimeMillis());
                return cookies.stream().filter(cookie -> cookie.matches(url)).toList();
            }
        };
    }
}
