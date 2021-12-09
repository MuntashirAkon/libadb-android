# LibADB Android

**Disclaimer:** This is an unaudited library. Use at your own risk.

## Get Started
### Connecting to ADB
Instead of doing everything manually, you can create a concrete implementation of the `AbsAdbConnectionManager` class. 
Example:

```java
public class AdbConnectionManager extends AbsAdbConnectionManager {
    private static AbsAdbConnectionManager INSTANCE;

    public static AbsAdbConnectionManager getInstance() throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new AdbConnectionManager();
        }
        return INSTANCE;
    }

    private PrivateKey mPrivateKey;
    private Certificate mCertificate;

    private AdbConnectionManager() throws Exception {
        // Set the API version whose `adbd` is running
        setApi(Build.VERSION.SDK_INT);
        // TODO: Load private key and certificate (along with public key) from some place such as KeyStore or file system.
        mPrivateKey = ...;
        mCertificate = ...;
        if (mPrivateKey == null) {
            // Generate a new key pair
            int keySize = 2048;
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair generateKeyPair = keyPairGenerator.generateKeyPair();
            PublicKey publicKey = generateKeyPair.getPublic();
            mPrivateKey = generateKeyPair.getPrivate();
            // Generate a certificate
            // On Android, if you aren't using hidden APIs, you can add this dependency in build.gradle:
            // implementation 'com.github.MuntashirAkon:sun-security-android:1.1'
            String subject = "CN=My Awesome App";
            String algorithmName = "SHA512withRSA";
            long expiryDate = System.currentTimeMillis() + 86400000;
            CertificateExtensions certificateExtensions = new CertificateExtensions();
            certificateExtensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                    new KeyIdentifier(publicKey).getIdentifier()));
            X500Name x500Name = new X500Name(subject);
            Date notBefore = new Date();
            Date notAfter = new Date(expiryDate);
            certificateExtensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
            CertificateValidity certificateValidity = new CertificateValidity(notBefore, notAfter);
            X509CertInfo x509CertInfo = new X509CertInfo();
            x509CertInfo.set("version", new CertificateVersion(2));
            x509CertInfo.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
            x509CertInfo.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
            x509CertInfo.set("subject", new CertificateSubjectName(x500Name));
            x509CertInfo.set("key", new CertificateX509Key(publicKey));
            x509CertInfo.set("validity", certificateValidity);
            x509CertInfo.set("issuer", new CertificateIssuerName(x500Name));
            x509CertInfo.set("extensions", certificateExtensions);
            X509CertImpl x509CertImpl = new X509CertImpl(x509CertInfo);
            x509CertImpl.sign(mPrivateKey, algorithmName);
            mCertificate = x509CertImpl;
            // TODO: Store the key pair to some place else
        }
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "MyAwesomeApp";
    }
}
```

Then, you can simply connect to ADB by invoking `AdbConnectionManager.getInstance().connect(host, port)`.

### Wireless Debugging
Internally, ADB over TCP and Wireless Debugging are very similar except Wireless Debugging requires an extra step of
_pairing_ the device. In order to pair a new device, you can simply invoke `AdbConnectionManager.getInstance().pair(host, port, pairingCode)`.
After the pairing, you can connect to ADB via the usual `connect()` methods without any additional steps.

In addition, it is necessary to bypass the hidden API restrictions until the Conscrypt issue has been fixed. To do that,
add the following dependency:
```
implementation 'org.lsposed.hiddenapibypass:hiddenapibypass:2.0'
```
Next, extend `android.os.Application` and add it in the manifest. In the extended class, add the following:
```java
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }
    }
```

### Opening ADB Shell for Executing Arbitrary Commands
Simply use `AdbConnectionManager.getInstance().openStream("shell:")`. This will return an `AdbStream` which can be used
to read/write to the ADB shell via `AdbStream#openInputStream()` and `AdbStream#openOutputStream()` methods
respectively like a normal Java `Process`. While it is possible to read/write in the same thread (first write and then
read), this is not recommended because the shell might be stuck indefinitely for commands such as `top`.

## For Java (non-Android) Projects
It is possible to modify this library to work on non-Android project. But it isn't supported because Spake2-Java only
provides stable releases for Android. However, you can incorporate this library in your project by manually compiling
Spake2 library for your platforms.

## Contributing
By contributing to this project, you permit your work to be released under the terms of GNU General Public License, 
Version 3 or later **or** Apache License, Version 2.0.

## License
Copyright 2021 Muntashir Al-Islam

Licensed under the GPLv3: https://www.gnu.org/licenses/gpl-3.0.html

_It was not possible to use a permissive license because it has GPL dependencies._