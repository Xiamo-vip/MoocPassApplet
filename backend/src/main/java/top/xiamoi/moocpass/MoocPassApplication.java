package top.xiamoi.moocpass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.xiamoi.moocpass.config.MoocProperties;
import top.xiamoi.moocpass.util.OkHttpUtil;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MoocProperties.class)
public class MoocPassApplication {
    private static final Logger log = LoggerFactory.getLogger(MoocPassApplication.class);
    private final MoocProperties moocProperties;

    public MoocPassApplication(MoocProperties moocProperties) {
        this.moocProperties = moocProperties;
    }

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        if (moocProperties.getProxy() != null && moocProperties.getProxy().isEnabled()
                && moocProperties.getProxy().getHost() != null && !moocProperties.getProxy().getHost().isBlank()
                && moocProperties.getProxy().getPort() > 0) {
            var p = moocProperties.getProxy();
            log.info("启用平台网络代理: {}:{} (类型: {})", p.getHost(), p.getPort(), p.getType());
            OkHttpUtil.setProxyConfig(new OkHttpUtil.ProxyConfig(
                p.isEnabled(), p.getType(), p.getHost(), p.getPort(), p.getUsername(), p.getPassword()
            ));
        } else {
            log.info("平台网络代理未启用，使用直连模式");
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(MoocPassApplication.class, args);
    }
}
