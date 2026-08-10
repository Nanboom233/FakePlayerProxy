package com.fakeplayerproxy.command;

import com.fakeplayerproxy.config.ProxyConfig;
import com.fakeplayerproxy.automation.UpstreamConnectRequest;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import java.util.Objects;

public final class ConnectCommandParser {
    private ConnectCommandParser() {
    }

    public static ProxyResult<UpstreamConnectRequest> parseConnectArgs(String[] args, ProxyConfig defaults) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(defaults, "defaults");

        if (args.length > 3) {
            return ProxyResult.failure(new ProxyError(
                    "command_too_many_arguments",
                    "Usage: /fpp connect [host] [port] [username]"));
        }

        String host = args.length >= 1 ? args[0] : defaults.targetHost();
        String portText = args.length >= 2 ? args[1] : Integer.toString(defaults.targetPort());
        String username = args.length == 3 ? args[2] : defaults.username();

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return ProxyResult.failure(new ProxyError(
                    "command_invalid_port",
                    "Port must be a number between 1 and 65535."));
        }

        try {
            return ProxyResult.success(new UpstreamConnectRequest(host, port, username));
        } catch (IllegalArgumentException e) {
            return ProxyResult.failure(new ProxyError("command_invalid_target", e.getMessage()));
        }
    }
}
