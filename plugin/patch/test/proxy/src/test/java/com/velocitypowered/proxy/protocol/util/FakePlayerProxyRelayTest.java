/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.util;

import static com.velocitypowered.proxy.crypto.EncryptionUtils.decryptRsa;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.velocitypowered.proxy.crypto.EncryptionUtils;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies the production carrier and RSA transformation with real key pairs.
 *
 * <p>The test follows the same byte flow as the client, Velocity, and the target.
 * It does not infer behavior from constants or source structure.
 */
class FakePlayerProxyRelayTest {

  private static KeyPair proxyKeyPair;
  private static KeyPair targetKeyPair;

  @BeforeAll
  static void generateKeys() {
    proxyKeyPair = EncryptionUtils.createRsaKeyPair(1024);
    targetKeyPair = EncryptionUtils.createRsaKeyPair(2048);
  }

  @Test
  void relaysStandardKeyResponseWithRealKeys() throws Exception {
    // Velocity decorates its proxy key with the public key from the target Hello.
    byte[] targetKey = targetKeyPair.getPublic().getEncoded();
    byte[] decoratedKey = FakePlayerProxyRelay.decoratePublicKey(
        proxyKeyPair.getPublic(), targetKey);
    byte[] secret = new byte[16];
    byte[] challenge = new byte[] {4, 8, 15, 16};
    Arrays.fill(secret, (byte) 23);

    // The client parses that key and sends its AES key with the Mod acknowledgement.
    var parsedProxyKey = EncryptionUtils.parseRsaPublicKey(decoratedKey);
    byte[] acknowledgement = new byte["FPPACK".length() + 1 + challenge.length];
    System.arraycopy("FPPACK".getBytes(StandardCharsets.US_ASCII), 0,
        acknowledgement, 0, "FPPACK".length());
    acknowledgement["FPPACK".length()] = 1;
    System.arraycopy(challenge, 0, acknowledgement, "FPPACK".length() + 1,
        challenge.length);
    byte[] encryptedSecret = FakePlayerProxyRelay.encryptRsa(parsedProxyKey, secret);
    byte[] encryptedAck = FakePlayerProxyRelay.encryptRsa(
        parsedProxyKey, acknowledgement);
    byte[] recoveredSecret = decryptRsa(proxyKeyPair, encryptedSecret);
    byte[] recoveredAck = decryptRsa(proxyKeyPair, encryptedAck);

    // Velocity recovers K, verifies the acknowledgement, and encrypts for the target.
    assertEquals(FakePlayerProxyRelay.ResponseKind.MOD,
        FakePlayerProxyRelay.classifyResponse(recoveredAck, challenge));
    assertArrayEquals(secret, decryptRsa(targetKeyPair,
        FakePlayerProxyRelay.encryptRsa(targetKeyPair.getPublic(), recoveredSecret)));
    assertArrayEquals(challenge, decryptRsa(targetKeyPair,
        FakePlayerProxyRelay.encryptRsa(targetKeyPair.getPublic(), challenge)));
  }

  @Test
  void classifiesVanillaAndInvalidResponses() throws Exception {
    byte[] challenge = new byte[] {1, 2, 3, 4};
    assertEquals(FakePlayerProxyRelay.ResponseKind.VANILLA,
        FakePlayerProxyRelay.classifyResponse(challenge.clone(), challenge));
    assertEquals(FakePlayerProxyRelay.ResponseKind.INVALID,
        FakePlayerProxyRelay.classifyResponse(new byte[] {9}, challenge));
  }

  @Test
  void enforcesCarrierBounds() throws GeneralSecurityException {
    assertThrows(GeneralSecurityException.class,
        () -> FakePlayerProxyRelay.decoratePublicKey(proxyKeyPair.getPublic(), new byte[513]));
  }
}
