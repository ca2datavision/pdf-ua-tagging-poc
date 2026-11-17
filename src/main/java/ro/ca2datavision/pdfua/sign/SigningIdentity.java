package ro.ca2datavision.pdfua.sign;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Who signs: a private key and the certificate chain that belongs to it, signer first.
 *
 * @param name the name shown for the signer
 */
public record SigningIdentity(String name, PrivateKey key, List<X509Certificate> chain) {

    /**
     * Creates a throw-away identity with a self-signed certificate, valid for 30 days.
     *
     * <p>For demonstrations and tests only. Nobody vouches for such a certificate: PDF viewers
     * show the signature as "validity unknown" or "not trusted". A real signature needs a
     * certificate from a trust service provider, usually kept in a key store or on a token.</p>
     */
    public static SigningIdentity selfSigned(String name) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();

        X500Name subject = new X500Name("CN=" + name.replace(",", " ") + ", OU=Test certificate without any trust");
        Instant now = Instant.now();
        try {
            X509CertificateHolder holder = new JcaX509v3CertificateBuilder(
                    subject, BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(30, ChronoUnit.DAYS)),
                    subject, keys.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
            return new SigningIdentity(name, keys.getPrivate(), List.of(new JcaX509CertificateConverter().getCertificate(holder)));
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
    }
}
