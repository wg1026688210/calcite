/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.milvus.sql.client.ssl;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;

/**
 * Factory for creating Netty {@link SslContext} with auto-generated self-signed certificate.
 *
 *
 * <p>Inspired by ShardingSphere's ProxySSLContext - zero configuration SSL.
 */
public final class MilvusSslContextFactory {

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  private final SslContext sslContext;

  private MilvusSslContextFactory() {
    try {
      KeyPair keyPair = generateRSAKeyPair();
      X509Certificate certificate = generateSelfSignedCertificate(keyPair);
      sslContext = SslContextBuilder.forServer(keyPair.getPrivate(), certificate)
          .sslProvider(SslProvider.JDK)
          .protocols("TLSv1", "TLSv1.1", "TLSv1.2")
          .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create SSL context", e);
    }
  }

  private static class SingletonHolder {
    private static final MilvusSslContextFactory INSTANCE = new MilvusSslContextFactory();
  }

  public static MilvusSslContextFactory getInstance() {
    return SingletonHolder.INSTANCE;
  }

  /**
   * Creates a new SSLEngine for SSL connections.
   *
   * @param alloc ByteBufAllocator
   * @return new SSLEngine
   */
  public SSLEngine newSSLEngine(ByteBufAllocator alloc) {
    return sslContext.newEngine(alloc);
  }

  private static KeyPair generateRSAKeyPair() throws Exception {
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
    keyGen.initialize(4096, new SecureRandom());
    return keyGen.generateKeyPair();
  }

  private static X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
    long now = System.currentTimeMillis();
    Date startDate = new Date(now - TimeUnit.DAYS.toMillis(1));
    Date endDate = new Date(now + TimeUnit.DAYS.toMillis(365 * 100));

    X500Name dnName = new X500NameBuilder(BCStyle.INSTANCE)
        .addRDN(BCStyle.CN, "Milvus SQL Client")
        .addRDN(BCStyle.OU, "Apache Calcite")
        .addRDN(BCStyle.O, "Apache Software Foundation")
        .build();

    BigInteger serialNumber = new BigInteger(Long.toString(now));

    ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA")
        .build(keyPair.getPrivate());

    JcaX509v3CertificateBuilder certBuilder =
        new JcaX509v3CertificateBuilder(dnName, serialNumber, startDate, endDate, dnName, keyPair.getPublic());

    return new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner));
  }
}
