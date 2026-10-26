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
package org.openhab.binding.danfosslink.internal.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.ECPoint;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.danfosslink.internal.DanfossLinkBindingConstants;

/**
 * MDG PACE pairing, binding the displayed password to both TLS public keys.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class Pairing {
    private static final X9ECParameters CURVE = TeleTrusTNamedCurves.getByName("brainpoolP320r1");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Pairing() {
    }

    public interface Exchange {
        void send(byte[] packet) throws IOException;

        byte[] receive() throws IOException;
    }

    public static void run(Exchange connection, byte[] password, byte[] serverPublicKey, byte[] clientPublicKey)
            throws IOException {
        try {
            connection.send(Wire.message(1).fixed32(1, 0xf09d8ca7).number(2, 1).number(3, 0).build());
            var hello = receive(connection, 2);
            if (hello.number(1) != 1 || hello.number(2) != 0) {
                throw new IOException("Unsupported PACE version");
            }
            byte[] key = passwordKey(password, serverPublicKey, clientPublicKey, hello.bytes(3));
            byte[] ciphertext = hello.bytes(5);
            if (ciphertext.length != 64) {
                throw new IOException("Unexpected PACE scalar length");
            }
            // LuaCrypto zero-pads the 16-byte Kpi for AES-256 and ignores the bignum IV
            // argument.
            var aes = Cipher.getInstance("AES/CBC/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 32), "AES"),
                    new IvParameterSpec(new byte[16]));
            BigInteger secret = new BigInteger(1, Arrays.copyOf(aes.doFinal(ciphertext), 40));
            Arrays.fill(key, (byte) 0);
            BigInteger first = scalar();
            connection.send(Wire.message(3).bytes(1, CURVE.getG().multiply(first).getEncoded(false)).build());
            ECPoint shared = point(receive(connection, 3).bytes(1)).multiply(first);
            ECPoint generator = CURVE.getG().multiply(secret).add(shared);
            if (generator.isInfinity()) {
                throw new IOException("Invalid PACE generator");
            }
            BigInteger second = scalar();
            ECPoint ours = generator.multiply(second);
            connection.send(Wire.message(4).bytes(1, ours.getEncoded(false)).build());
            ECPoint theirs = point(receive(connection, 4).bytes(1));
            byte[] macKey = MessageDigest.getInstance("SHA-256").digest(theirs.multiply(second).getEncoded(false));
            try {
                connection.send(Wire.message(5).bytes(1, proof(macKey, theirs.getEncoded(false))).build());
                byte[] received = receive(connection, 5).bytes(1);
                if (!MessageDigest.isEqual(received, proof(macKey, ours.getEncoded(false)))) {
                    throw new IOException("PACE proof mismatch; obtain a fresh pairing code");
                }
            } finally {
                Arrays.fill(macKey, (byte) 0);
            }
            connection.send(Wire.message(6).text(1, DanfossLinkBindingConstants.APP_NAME).build());
            receive(connection, 6).text(1);
        } catch (GeneralSecurityException e) {
            throw new IOException("PACE cryptographic exchange failed", e);
        }
    }

    public static byte[] passwordKey(byte[] password, byte[] server, byte[] client, byte[] salt) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var out = new DataOutputStream(buffer);
        for (byte[] value : new byte[][] { password, server, client }) {
            if (value.length > 65535) {
                throw new IOException("PACE input too long");
            }
            out.writeShort(value.length);
            out.write(value);
        }
        out.writeByte(255);
        var kdf = new PKCS5S2ParametersGenerator(new SHA256Digest());
        kdf.init(buffer.toByteArray(), salt, 1024);
        return ((KeyParameter) kdf.generateDerivedParameters(128)).getKey();
    }

    public static byte[] proof(byte[] key, byte[] point) throws IOException {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(point)).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot calculate PACE proof", e);
        }
    }

    private static BigInteger scalar() {
        BigInteger value;
        do {
            value = new BigInteger(320, RANDOM);
        } while (value.signum() == 0 || value.compareTo(CURVE.getN()) >= 0);
        return value;
    }

    private static ECPoint point(byte[] encoded) throws IOException {
        try {
            ECPoint point = CURVE.getCurve().decodePoint(encoded);
            if (point.isInfinity() || !point.isValid() || !point.multiply(CURVE.getN()).isInfinity()) {
                throw new IOException("Invalid PACE point");
            }
            return point;
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed PACE point", e);
        }
    }

    private static Wire.Fields receive(Exchange connection, int type) throws IOException {
        var message = Wire.decode(connection.receive());
        if (message.type() != type) {
            throw new IOException("Unexpected PACE message type " + message.type());
        }
        return message.fields();
    }
}
