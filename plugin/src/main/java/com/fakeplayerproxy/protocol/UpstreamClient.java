package com.fakeplayerproxy.protocol;

import com.fakeplayerproxy.util.ProxyResult;
import com.fakeplayerproxy.automation.InputState;

public interface UpstreamClient extends AutoCloseable {
    void connect(Listener listener) throws Exception;

    void disconnect(String reason);

    ProxyResult<Void> look(float yaw, float pitch);

    ProxyResult<Void> turn(float yawDelta, float pitchDelta);

    ProxyResult<Void> selectHotbar(int slotOneBased);

    ProxyResult<Void> sendInput(InputState inputState);

    ProxyResult<Void> swingMainHand();

    ProxyResult<Void> useMainHand();

    ProxyResult<Void> dropSelectedItem(boolean stack);

    ProxyResult<Void> swapHands();

    @Override
    void close();

    interface Listener {
        void onTransportConnected();

        void onPlayReady();

        void onDisconnected(String safeReason, Throwable cause);

        void onError(String safeMessage, Throwable cause);
    }
}
