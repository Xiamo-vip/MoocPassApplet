package top.xiamoi.moocpass.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mooc")
public class MoocProperties {
    private String encryptionKey = "";
    private String appId = "";
    private String appSecret = "";
    private int workerSlots = 4;
    private int globalSlots = 30;
    private int userSlots = 2;
    private int leaseSeconds = 90;
    private int logRetentionDays = 30;
    private int historyRetentionDays = 90;
    private String zone = "Asia/Shanghai";
    private boolean chaoxingEnabled;
    private boolean zhyEnabled;
    private ProxyProperties proxy = new ProxyProperties();

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String type = "http";
        private String host = "";
        private int port = 0;
        private String username = "";
        private String password = "";
        private boolean applyToOutbound = false;
    }
}
