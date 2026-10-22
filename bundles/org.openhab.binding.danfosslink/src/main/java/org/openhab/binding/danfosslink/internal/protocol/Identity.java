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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * An MDG client identity, persisted independently of a connection.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public final class Identity {
    static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    public final X509CertificateHolder certificate;
    public final AsymmetricKeyParameter privateKey;

    public Identity(X509CertificateHolder certificate, AsymmetricKeyParameter privateKey) {
        this.certificate = certificate;
        this.privateKey = privateKey;
    }

    public static Identity generate() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC", PROVIDER);
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var subject = new X500Name("C=DK,O=Trifork,CN=localhost");
        var now = Instant.now();
        var extensions = new JcaX509ExtensionUtils();
        var builder = new JcaX509v3CertificateBuilder(subject,
                new BigInteger(159, new SecureRandom()).add(BigInteger.ONE), Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(7300, ChronoUnit.DAYS)), subject, pair.getPublic());
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                extensions.createSubjectKeyIdentifier(pair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                extensions.createAuthorityKeyIdentifier(pair.getPublic()));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        var certificate = builder
                .build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(PROVIDER).build(pair.getPrivate()));
        return new Identity(certificate, PrivateKeyFactory.createKey(pair.getPrivate().getEncoded()));
    }

    public String encode() throws IOException {
        return pem("PRIVATE KEY", PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey).getEncoded())
                + pem("CERTIFICATE", certificate.getEncoded());
    }

    public static Identity decode(String encoded) throws IOException {
        try (var reader = new PEMParser(new StringReader(encoded))) {
            if (!(reader.readObject() instanceof PrivateKeyInfo key)
                    || !(reader.readObject() instanceof X509CertificateHolder cert)) {
                throw new IOException("Invalid persisted client identity");
            }
            return new Identity(cert, PrivateKeyFactory.createKey(key));
        }
    }

    public static String peerId(X509CertificateHolder certificate) throws Exception {
        // MDG hashes the subjectPublicKey BIT STRING, not the certificate or complete SPKI.
        return HexFormat.ofDelimiter(":").formatHex(MessageDigest.getInstance("SHA-1")
                .digest(certificate.getSubjectPublicKeyInfo().getPublicKeyData().getBytes()));
    }

    public static byte[] publicKeyPem(X509CertificateHolder certificate) throws IOException {
        return pem("PUBLIC KEY", certificate.getSubjectPublicKeyInfo().getEncoded())
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static String pem(String type, byte[] data) throws IOException {
        var output = new StringWriter();
        try (var writer = new PemWriter(output)) {
            writer.writeObject(new PemObject(type, data));
        }
        // PACE binds the exact OpenSSL PEM representation, including LF line endings.
        return output.toString().replace("\r\n", "\n");
    }
}
