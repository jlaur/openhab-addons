/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.danfosslink.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Protocol boundaries and PACE exchange with an independently implemented
 * server role.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
class ProtocolTest {
    @Test
    void framingRejectsTruncationAndOversize() throws Exception {
        var output = new ByteArrayOutputStream();
        Wire.write(output, new byte[] { 1, 2, 3 });
        assertArrayEquals(new byte[] { 0, 3, 1, 2, 3 }, output.toByteArray());
        assertArrayEquals(new byte[] { 1, 2, 3 }, Wire.read(new ByteArrayInputStream(output.toByteArray())));
        assertThrows(IOException.class, () -> Wire.read(new ByteArrayInputStream(new byte[] { 0, 2, 1 })));
        assertThrows(IOException.class, () -> Wire.read(new ByteArrayInputStream(new byte[] { 0, 0 })));
        assertThrows(IOException.class, () -> Wire.write(output, new byte[65536]));
    }

    @Test
    void protobufHandlesRepeatedAndUnknownFields() throws Exception {
        var packet = Wire.message(1).fixed32(1, 0xf09d8ca8).number(2, 1).number(3, 0).text(4, "one").text(4, "two")
                .number(99, 123456).build();
        assertEquals("010da88c9df01001180022036f6e65220374776f9806c0c407", HexFormat.of().formatHex(packet));
        var fields = Wire.decode(packet).fields();
        assertEquals(0xf09d8ca8L, fields.number(1));
        assertEquals(2, fields.repeatedBytes(4).size());
        assertEquals("two", fields.text(4));
        assertThrows(IOException.class, () -> Wire.decodeFields(new byte[] { 10, 10, 1 }));
        assertThrows(IOException.class, () -> Wire.decodeFields(new byte[] { 0 }));
        assertThrows(IOException.class, () -> Wire.decodeFields(new byte[] { 8, (byte) 128 }));
    }

    @Test
    void identitySurvivesEncoding() throws Exception {
        var identity = Identity.generate();
        var loaded = Identity.decode(identity.encode());
        assertArrayEquals(identity.certificate.getEncoded(), loaded.certificate.getEncoded());
        assertEquals(Identity.peerId(identity.certificate), Identity.peerId(loaded.certificate));

        assertFalse(new String(Identity.publicKeyPem(loaded.certificate), StandardCharsets.US_ASCII).contains("\r"));
    }

    @Test
    void worldRequiresCorrectEnvelopeAndSuccess() throws Exception {
        String response = """
                {"id":1,"data":{"type":"JsonWebResponse","data":{"status":200,"content":{"allRooms":[]}}}}
                """;
        assertTrue(CloudClient.response(response.getBytes(StandardCharsets.UTF_8), 1).getAsJsonObject()
                .getAsJsonArray("allRooms").isEmpty());
        var rejected = assertThrows(CloudClient.ControllerResponseException.class,
                () -> CloudClient.response(response.replace("200", "403").getBytes(StandardCharsets.UTF_8), 1));
        assertEquals(403, rejected.status());
        assertThrows(IOException.class, () -> CloudClient
                .response(response.replace("\"id\":1", "\"id\":2").getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(IOException.class, () -> CloudClient.response("{}".getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(IOException.class, () -> CloudClient.response("not JSON".getBytes(StandardCharsets.UTF_8), 1));
    }

    @Test
    void paceAuthenticatesBothPeersAndRejectsWrongPassword() throws Exception {
        Pairing.run(new PaceServer(), "12345".getBytes(StandardCharsets.US_ASCII), new byte[] { 1 }, new byte[] { 2 });
        assertThrows(IOException.class, () -> Pairing.run(new PaceServer(), "99999".getBytes(StandardCharsets.US_ASCII),
                new byte[] { 1 }, new byte[] { 2 }));
    }

    private static final class PaceServer implements Pairing.Exchange {
        private final ArrayDeque<byte[]> replies = new ArrayDeque<>();
        private final org.bouncycastle.asn1.x9.X9ECParameters curve = TeleTrusTNamedCurves.getByName("brainpoolP320r1");
        private ECPoint generator = curve.getG();
        private ECPoint own = curve.getG();
        private byte[] expectedProof = new byte[0];

        @Override
        public void send(byte[] packet) throws IOException {
            try {
                var message = Wire.decode(packet);
                switch (message.type()) {
                    case 1 -> {
                        byte[] salt = new byte[] { 3, 4, 5 };
                        byte[] key = Pairing.passwordKey("12345".getBytes(StandardCharsets.US_ASCII), new byte[] { 1 },
                                new byte[] { 2 }, salt);
                        var aes = Cipher.getInstance("AES/CBC/NoPadding");
                        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 32), "AES"),
                                new IvParameterSpec(new byte[16]));
                        byte[] plaintext = new byte[64];
                        plaintext[39] = 7;
                        replies.add(Wire.message(2).number(1, 1).number(2, 0).bytes(3, salt).bytes(4, new byte[16])
                                .bytes(5, aes.doFinal(plaintext)).build());
                    }
                    case 3 -> {
                        var client = curve.getCurve().decodePoint(message.fields().bytes(1));
                        generator = curve.getG().multiply(BigInteger.valueOf(7))
                                .add(client.multiply(BigInteger.valueOf(11)));
                        replies.add(Wire.message(3)
                                .bytes(1, curve.getG().multiply(BigInteger.valueOf(11)).getEncoded(false)).build());
                    }
                    case 4 -> {
                        var client = curve.getCurve().decodePoint(message.fields().bytes(1));
                        own = generator.multiply(BigInteger.valueOf(13));
                        byte[] key = MessageDigest.getInstance("SHA-256")
                                .digest(client.multiply(BigInteger.valueOf(13)).getEncoded(false));
                        replies.add(Wire.message(4).bytes(1, own.getEncoded(false)).build());
                        expectedProof = Pairing.proof(key, own.getEncoded(false));
                        replies.add(Wire.message(5).bytes(1, Pairing.proof(key, client.getEncoded(false))).build());
                    }
                    case 5 -> {
                        if (!MessageDigest.isEqual(expectedProof, message.fields().bytes(1))) {
                            throw new IOException("Client PACE proof did not authenticate");
                        }
                    }
                    case 6 -> replies.add(Wire.message(6).text(1, "Mock controller").build());
                    default -> throw new IOException("Unexpected test message");
                }
            } catch (Exception e) {
                throw new IOException("Mock pairing server failed", e);
            }
        }

        @Override
        public byte[] receive() {
            return replies.remove();
        }
    }

    @Test
    void rejectionDiagnosticsIncludeErrorsButExcludeUnrelatedResponseData() {
        var response = com.google.gson.JsonParser.parseString("""
                {"statusDescription":"Bad Request","headers":["Authorization: secret"],
                "content":{"message":"Invalid room zone","privateKey":"secret", "allRooms":[{"name":"Private"}]}}
                """).getAsJsonObject();
        String details = CloudClient.errorDetails(response);
        var parsed = com.google.gson.JsonParser.parseString(details).getAsJsonObject();
        assertEquals("Invalid room zone", parsed.get("message").getAsString());
        assertFalse(details.contains("secret"));
        assertFalse(details.contains("Private"));
        response.add("content", com.google.gson.JsonParser.parseString("""
                {"errorMessages":["Invalid room zone", {"privateKey":"secret"}, null, 42], "allRooms":[]}
                """));
        details = CloudClient.errorDetails(response);
        assertTrue(details.contains("\"errorMessages\":[\"Invalid room zone\"]"));
        assertFalse(details.contains("secret"));
        assertFalse(details.contains("allRooms"));
        response.addProperty("content", "Bad request\nDetails");
        assertTrue(CloudClient.errorDetails(response).contains("Bad request\\nDetails"));
        assertFalse(CloudClient.errorDetails(response).contains("\n"));
        response.addProperty("content", "x".repeat(20000));
        assertEquals(2048 + " [truncated]".length(), CloudClient.errorDetails(response).length());
    }
}
