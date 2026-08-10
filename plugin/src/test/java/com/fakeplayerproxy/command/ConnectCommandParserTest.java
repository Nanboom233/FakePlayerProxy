package com.fakeplayerproxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fakeplayerproxy.config.ProxyConfig;
import com.fakeplayerproxy.automation.UpstreamConnectRequest;
import com.fakeplayerproxy.util.ProxyResult;
import org.junit.jupiter.api.Test;

final class ConnectCommandParserTest {
    private final ProxyConfig defaults = new ProxyConfig("127.0.0.1", 25566, "ProxyBot");

    @Test
    void emptyArgsUseDefaults() {
        ProxyResult<UpstreamConnectRequest> result = ConnectCommandParser.parseConnectArgs(new String[0], defaults);

        assertTrue(result.isSuccess());
        assertEquals("127.0.0.1", result.valueOrThrow().host());
        assertEquals(25566, result.valueOrThrow().port());
        assertEquals("ProxyBot", result.valueOrThrow().username());
    }

    @Test
    void explicitArgsOverrideDefaults() {
        ProxyResult<UpstreamConnectRequest> result =
                ConnectCommandParser.parseConnectArgs(new String[] {"localhost", "25567", "OtherBot"}, defaults);

        assertTrue(result.isSuccess());
        assertEquals("localhost", result.valueOrThrow().host());
        assertEquals(25567, result.valueOrThrow().port());
        assertEquals("OtherBot", result.valueOrThrow().username());
    }

    @Test
    void invalidPortReturnsTypedError() {
        ProxyResult<UpstreamConnectRequest> result =
                ConnectCommandParser.parseConnectArgs(new String[] {"localhost", "bad-port"}, defaults);

        assertEquals("command_invalid_port", result.errorOrThrow().code());
    }

    @Test
    void tooManyArgumentsReturnTypedError() {
        ProxyResult<UpstreamConnectRequest> result =
                ConnectCommandParser.parseConnectArgs(new String[] {"a", "1", "b", "extra"}, defaults);

        assertEquals("command_too_many_arguments", result.errorOrThrow().code());
    }
}
