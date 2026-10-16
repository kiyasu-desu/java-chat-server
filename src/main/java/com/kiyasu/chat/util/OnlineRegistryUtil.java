package com.kiyasu.chat.util;

import com.kiyasu.chat.service.ClientService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线用户注册表
 */
public class OnlineRegistryUtil {
    private final ConcurrentHashMap<String, ClientService> map = new ConcurrentHashMap<>();

    public void register(String id, ClientService clientService) {
        map.put(id, clientService);
    }

    public void unregister(String id) {
        map.remove(id);
    }

    public ClientService getClient(String id) {
        return map.get(id);
    }

    public boolean isOnline(String id) {
        return map.containsKey(id);
    }

    public List<String> getOnlineIds() {
        return new ArrayList<>(map.keySet());
    }
}
