package com.fakeplayerproxy.automation;

import com.fakeplayerproxy.config.ProxyConfig;

public record UpstreamConnectRequest(String host, int port, String username) {
    public UpstreamConnectRequest {
        ProxyConfig normalized = new ProxyConfig(host, port, username);
        host = normalized.targetHost();
        port = normalized.targetPort();
        username = normalized.username();
    }

    public String targetLabel() {
        return username + "@" + host + ":" + port;
    }
}
