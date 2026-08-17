package com.fakeplayerproxy.utils;

public sealed interface Result<T, E> permits Result.Success, Result.Failure {
    record Success<T, E>(T value) implements Result<T, E> {}

    record Failure<T, E>(E error) implements Result<T, E> {}
}
