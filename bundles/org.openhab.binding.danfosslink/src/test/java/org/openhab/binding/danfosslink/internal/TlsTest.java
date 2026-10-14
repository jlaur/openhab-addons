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

import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.bc.BcECContentSignerBuilder;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.ClientCertificateType;
import org.bouncycastle.tls.DefaultTlsServer;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.TlsServerProtocol;
import org.bouncycastle.tls.TlsUtils;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real TLS records against a local mutual-TLS server with explicit Brainpool
 * certificate parameters.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@Timeout(30)
class TlsTest {
    @Test
    void cloudTrustRejectsUnknownAuthorityAndWrongService() throws Exception {
        var caKey = explicitIdentity();
        var ca = signedCertificate(caKey, "CN=Test MDG CA", caKey, "CN=Test MDG CA", true);
        var serverKey = explicitIdentity();
        var server = signedCertificate(serverKey, "CN=MDG Control v2", caKey, "CN=Test MDG CA", false);
        var verifier = Transport.cloudTrust(new java.io.ByteArrayInputStream(
                Identity.pem("CERTIFICATE", ca.getEncoded()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        verifier.verify(java.util.List.of(server));
        var wrongService = signedCertificate(serverKey, "CN=Other service", caKey, "CN=Test MDG CA", false);
        assertThrows(IOException.class, () -> verifier.verify(java.util.List.of(wrongService)));
        var impostor = signedCertificate(serverKey, "CN=MDG Control v2", explicitIdentity(), "CN=Test MDG CA", false);
        assertThrows(java.security.cert.CertPathValidatorException.class,
                () -> verifier.verify(java.util.List.of(impostor)));
    }

    private static org.bouncycastle.cert.X509CertificateHolder signedCertificate(Identity key, String subject,
            Identity issuer, String issuerName, boolean ca) throws Exception {
        var signature = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withECDSA");
        var signer = new BcECContentSignerBuilder(signature,
                new DefaultDigestAlgorithmIdentifierFinder().find(signature)).build(issuer.privateKey);
        var builder = new X509v3CertificateBuilder(new X500Name(issuerName), BigInteger.ONE,
                Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)),
                new X500Name(subject), key.certificate.getSubjectPublicKeyInfo());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(ca));
        return builder.build(signer);
    }

    @Test
    void explicitBrainpoolCertificateAndClientAuthentication() throws Exception {
        Identity server = explicitIdentity();
        Identity client = Identity.generate();
        try (var listener = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(10000);
            var result = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(10000);
                    var crypto = new BcTlsCrypto();
                    var protocol = new TlsServerProtocol(socket.getInputStream(), socket.getOutputStream());
                    protocol.accept(new DefaultTlsServer(crypto) {
                        @Override
                        protected ProtocolVersion[] getSupportedVersions() {
                            return new ProtocolVersion[] { ProtocolVersion.TLSv12 };
                        }

                        @Override
                        protected int[] getSupportedCipherSuites() {
                            return new int[] { CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 };
                        }

                        @Override
                        protected TlsCredentialedSigner getECDSASignerCredentials() throws IOException {
                            return new BcDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto,
                                    server.privateKey,
                                    new Certificate(new TlsCertificate[] {
                                            crypto.createCertificate(server.certificate.getEncoded()) }),
                                    new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa));
                        }

                        @Override
                        public CertificateRequest getCertificateRequest() {
                            return new CertificateRequest(new short[] { ClientCertificateType.ecdsa_sign },
                                    TlsUtils.getDefaultSupportedSignatureAlgorithms(context), null);
                        }

                        @Override
                        public void notifyClientCertificate(
                                @org.eclipse.jdt.annotation.Nullable Certificate certificate) throws IOException {
                            assertArrayEquals(client.certificate.getEncoded(),
                                    java.util.Objects.requireNonNull(certificate).getCertificateAt(0).getEncoded());
                        }
                    });
                    byte[] request = Wire.read(protocol.getInputStream());
                    assertArrayEquals(new byte[] { 1, 2, 3 }, request);
                    Wire.write(protocol.getOutputStream(),
                            "{\"id\":1,\"data\":{\"type\":\"JsonWebResponse\",\"data\":{\"status\":200,\"content\":{\"allRooms\":[]}}}}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return true;
                }
            });
            try (var peer = Transport.connect("127.0.0.1", listener.getLocalPort(), new Operation())) {
                peer.startTls(client, true, Transport.pinned(server.certificate));
                peer.send(new byte[] { 1, 2, 3 });
                assertTrue(
                        CloudClient.response(peer.receive(), 1).getAsJsonObject().getAsJsonArray("allRooms").isEmpty());
            }
            assertTrue(result.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void changedControllerCertificateIsRejected() throws Exception {
        var expected = explicitIdentity();
        var different = explicitIdentity();
        assertThrows(IOException.class,
                () -> Transport.pinned(expected.certificate).verify(java.util.List.of(different.certificate)));
        Transport.pinned(expected.certificate).verify(java.util.List.of(expected.certificate));
    }

    @Test
    void stockJavaRejectsExplicitBrainpoolPublicKey() throws Exception {
        var key = explicitIdentity().certificate.getSubjectPublicKeyInfo().getEncoded();
        var factory = java.security.KeyFactory.getInstance("EC", "SunEC");
        assertThrows(java.security.spec.InvalidKeySpecException.class,
                () -> factory.generatePublic(new java.security.spec.X509EncodedKeySpec(key)));
    }

    private static Identity explicitIdentity() throws Exception {
        var parameters = TeleTrusTNamedCurves.getByName("brainpoolP320r1");
        var generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(new ECDomainParameters(parameters), new SecureRandom()));
        var keys = generator.generateKeyPair();
        var subject = new X500Name("CN=Mock controller");
        var signature = new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withECDSA");
        var signer = new BcECContentSignerBuilder(signature,
                new DefaultDigestAlgorithmIdentifierFinder().find(signature)).build(keys.getPrivate());
        var cert = new X509v3CertificateBuilder(subject, BigInteger.ONE, Date.from(Instant.now().minusSeconds(60)),
                Date.from(Instant.now().plusSeconds(3600)), subject,
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(keys.getPublic())).build(signer);
        assertFalse(cert.getSubjectPublicKeyInfo().getAlgorithm()
                .getParameters() instanceof org.bouncycastle.asn1.ASN1ObjectIdentifier);
        return new Identity(cert, keys.getPrivate());
    }
}
