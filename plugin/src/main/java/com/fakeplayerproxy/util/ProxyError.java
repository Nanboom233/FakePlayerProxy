package com.fakeplayerproxy.util;

import java.util.Objects;

public record ProxyError(String code, String safeMessage) {
    public ProxyError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(safeMessage, "safeMessage");
    }
}
