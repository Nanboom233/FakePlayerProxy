package com.fakeplayerproxy.util;

import java.util.Objects;
import java.util.Optional;

public final class ProxyResult<T> {
    private final T value;
    private final ProxyError error;

    private ProxyResult(T value, ProxyError error) {
        this.value = value;
        this.error = error;
    }

    public static <T> ProxyResult<T> success(T value) {
        return new ProxyResult<>(value, null);
    }

    public static ProxyResult<Void> success() {
        return new ProxyResult<>(null, null);
    }

    public static <T> ProxyResult<T> failure(ProxyError error) {
        return new ProxyResult<>(null, Objects.requireNonNull(error, "error"));
    }

    public boolean isSuccess() {
        return error == null;
    }

    public T valueOrThrow() {
        if (!isSuccess()) {
            throw new IllegalStateException("Cannot read value from failed result: " + error.code());
        }
        return value;
    }

    public Optional<ProxyError> error() {
        return Optional.ofNullable(error);
    }

    public ProxyError errorOrThrow() {
        if (isSuccess()) {
            throw new IllegalStateException("Cannot read error from successful result.");
        }
        return error;
    }
}
