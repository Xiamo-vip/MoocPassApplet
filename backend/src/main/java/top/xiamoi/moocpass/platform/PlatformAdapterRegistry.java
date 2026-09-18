package top.xiamoi.moocpass.platform;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlatformAdapterRegistry {

    private final Map<String, MoocPlatformAdapter> adapterMap = new HashMap<>();

    public PlatformAdapterRegistry(List<MoocPlatformAdapter> adapters) {
        for (MoocPlatformAdapter adapter : adapters) {
            adapterMap.put(adapter.getPlatformCode().toLowerCase(), adapter);
        }
    }

    public MoocPlatformAdapter getAdapter(String platformCode) {
        if (platformCode == null) {
            return null;
        }
        return adapterMap.get(platformCode.toLowerCase());
    }

    public List<MoocPlatformAdapter> getAllAdapters() {
        return new ArrayList<>(adapterMap.values());
    }
}
