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

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * TLS 1.2 over a direct cloud socket or the relay stream, using BC's
 * lightweight TLS implementation.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
final class Transport implements Closeable {
    private final Socket socket;
    private InputStream input;
    private OutputStream output;
    private final List<X509CertificateHolder> certificates = new ArrayList<>();

    private Transport(Socket socket, long deadline) throws IOException {
        this.socket = socket;
        input = new FilterInputStream(socket.getInputStream()) {
            private void remaining() throws IOException {
                long millis = (deadline - System.nanoTime()) / 1_000_000;
                if (millis <= 0) {
                    throw new SocketTimeoutException("Operation deadline exceeded");
                }
                socket.setSoTimeout((int) Math.min(millis, 30000));
            }

            @Override
            public int read() throws IOException {
                remaining();
                return super.read();
            }

            @Override
            public int read(byte @org.eclipse.jdt.annotation.Nullable [] bytes, int offset, int length)
                    throws IOException {
                remaining();
                return in.read(bytes, offset, length);
            }
        };
        output = socket.getOutputStream();
    }

    static Transport connect(String host, int port, Operation operation) throws IOException {
        var socket = new Socket();
        operation.add(socket);
        try {
            socket.connect(new InetSocketAddress(host, port), 15000);
            socket.setTcpNoDelay(true);
            return new Transport(socket, System.nanoTime() + 90_000_000_000L);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    interface Verifier {
        void verify(List<X509CertificateHolder> chain) throws Exception;
    }

    void startTls(Identity identity, boolean controller, Verifier verifier) throws IOException {
        var crypto = new BcTlsCrypto();
        var protocol = new TlsClientProtocol(input, output);
        protocol.connect(new DefaultTlsClient(crypto) {
            @Override
            protected ProtocolVersion[] getSupportedVersions() {
                return new ProtocolVersion[] { ProtocolVersion.TLSv12 };
            }

            @Override
            protected int[] getSupportedCipherSuites() {
                return controller ? new int[] { CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 }
                        : new int[] { CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 };
            }

            @Override
            @SuppressWarnings("rawtypes")
            protected Vector getSupportedGroups(@org.eclipse.jdt.annotation.Nullable Vector roles) {
                // Trifork used group 29 for brainpoolP320r1, conflicting with today's x25519.
                // Omitting this extension avoids the collision; the documented peer selects
                // P-256 ECDHE.
                return controller ? new Vector() : super.getSupportedGroups(roles);
            }

            @Override
            public TlsAuthentication getAuthentication() {
                return new TlsAuthentication() {
                    @Override
                    public void notifyServerCertificate(
                            @org.eclipse.jdt.annotation.Nullable TlsServerCertificate server) throws IOException {
                        List<X509CertificateHolder> chain = new ArrayList<>();
                        for (var cert : java.util.Objects.requireNonNull(server).getCertificate()
                                .getCertificateList()) {
                            chain.add(new X509CertificateHolder(cert.getEncoded()));
                        }
                        if (chain.isEmpty()) {
                            throw new TlsFatalAlert(AlertDescription.bad_certificate);
                        }
                        try {
                            verifier.verify(chain);
                        } catch (Exception e) {
                            throw new IOException("Server certificate validation failed", e);
                        }
                        certificates.addAll(chain);
                    }

                    @Override
                    public TlsCredentials getClientCredentials(
                            @org.eclipse.jdt.annotation.Nullable CertificateRequest request) throws IOException {
                        var signature = new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa);
                        java.util.Objects.requireNonNull(request);
                        if (request.getSupportedSignatureAlgorithms() != null
                                && !request.getSupportedSignatureAlgorithms().contains(signature)) {
                            throw new TlsFatalAlert(AlertDescription.handshake_failure);
                        }
                        return new BcDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto,
                                identity.privateKey, new Certificate(new TlsCertificate[] {
                                        crypto.createCertificate(identity.certificate.getEncoded()) }),
                                signature);
                    }
                };
            }
        });
        input = protocol.getInputStream();
        output = protocol.getOutputStream();
    }

    static Verifier pinned(X509CertificateHolder expected) {
        return chain -> {
            if (!MessageDigest.isEqual(expected.getEncoded(), chain.getFirst().getEncoded())) {
                throw new IOException("Controller certificate differs from the pairing");
            }
        };
    }

    static Verifier cloudTrust(InputStream bundle) throws Exception {
        var converter = new JcaX509CertificateConverter().setProvider(Identity.PROVIDER);
        var anchors = new HashSet<TrustAnchor>();
        try (var reader = new PEMParser(
                new java.io.InputStreamReader(bundle, java.nio.charset.StandardCharsets.US_ASCII))) {
            Object object;
            while ((object = reader.readObject()) != null) {
                if (!(object instanceof X509CertificateHolder holder)) {
                    throw new IOException("Unexpected object in CA bundle");
                }
                anchors.add(new TrustAnchor(converter.getCertificate(holder), null));
            }
        }
        if (anchors.isEmpty()) {
            throw new IOException("Empty CA bundle");
        }
        return chain -> {
            var leaf = chain.getFirst();
            var names = leaf.getSubject().getRDNs(BCStyle.CN);
            // This private PKI identifies the service by CN, not by an Internet DNS name.
            if (names.length != 1 || !"MDG Control v2".equals(names[0].getFirst().getValue().toString())) {
                throw new IOException("Unexpected MDG control service identity");
            }
            List<X509Certificate> path = new ArrayList<>();
            for (var cert : chain) {
                X509Certificate converted = converter.getCertificate(cert);
                if (anchors.stream().noneMatch(anchor -> converted.equals(anchor.getTrustedCert()))) {
                    path.add(converted);
                }
            }
            if (path.isEmpty()) {
                throw new IOException("Missing cloud server leaf certificate");
            }
            var parameters = new PKIXParameters(anchors);
            // The app's private CA bundle provides no online revocation service.
            parameters.setRevocationEnabled(false);
            parameters.setDate(new Date());
            var factory = CertificateFactory.getInstance("X.509", Identity.PROVIDER);
            CertPathValidator.getInstance("PKIX", Identity.PROVIDER).validate(factory.generateCertPath(path),
                    parameters);
        };
    }

    X509CertificateHolder peerCertificate() throws IOException {
        if (certificates.isEmpty()) {
            throw new IOException("TLS has not authenticated a peer");
        }
        return certificates.getFirst();
    }

    void send(byte[] packet) throws IOException {
        Wire.write(output, packet);
    }

    byte[] receive() throws IOException {
        return Wire.read(input);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
