# LibADB Android

ADB library for Android. It enables an app to connect to the ADB daemon (`adbd` process) belonging to the same or a
different device and execute arbitrary services or commands (via `shell:` service).

**Disclaimer:** This library has never gone through a security audit. Please, proceed with caution if security is
crucial for your app. Avoid using the APIs for reasons other than connecting or using ADB. For the safety of your app
and its users, open a remote service instead of using ADB and ask the user to disconnect Wireless debugging.

## Getting Started
### Adding Dependencies
LibADB Android is available via JitPack.

```groovy
// Top level build file
repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

// Add to dependencies section
dependencies {
    // Add this library
    implementation 'com.github.MuntashirAkon:libadb-android:1.0.1'
    
    // Library to generate X509Certificate. You can also use BouncyCastle for
    // this. See example for use-case.
    // implementation 'com.github.MuntashirAkon:sun-security-android:1.1'

    // Bypass hidden API if you want to use the Android default Conscrypt in
    // Android 9 (Pie) or later. It also requires additional steps. See
    // https://github.com/LSPosed/AndroidHiddenApiBypass to find out more about
    // this.
    // implementation 'org.lsposed.hiddenapibypass:hiddenapibypass:2.0'

    // Use custom Conscrypt library. If you want to connect to a remote ADB
    // daemon instead of the device the app is currently running or do not want
    // to bypass hidden API, this is the recommended choice.
    implementation 'org.conscrypt:conscrypt-android:2.5.2'
}
```

If you're using the custom Conscrypt library in order to connect to a remote ADB daemon and the app targets Android
version below 4.4, you have to extend `android.app.Application` to apply fixes for the random number generation:
```java
public class MyAwesomeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Fix random number generation in Android versions below 4.4.
        PRNGFixes.apply();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Uncomment the following line if you want to bypass hidden API as
        // described above.
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        //     HiddenApiBypass.addHiddenApiExemptions("L");
        // }
    }
}
```

**Notice:** Conscrypt only supports API 9 (Gingerbread) or later, meaning you cannot use ADB pairing or any TLSv1.3
features in API less than 9. The corresponding methods are already annotated properly. So, you don't have to worry about
compatibility issues that may arise when your app's minimum SDK is set to one of the unsupported versions.

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
        // TODO: Load private key and certificate (along with public key) from
        //  some place such as KeyStore or file system.
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
            // On Android, it requires sun.security-android library as mentioned
            // above.
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
            // TODO: Store the key pair to some place else.
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

Then, you can simply connect to ADB daemon by invoking `AdbConnectionManager.getInstance().connect(host, port)`.

### Wireless Debugging
Internally, ADB over TCP and Wireless Debugging are very similar except Wireless Debugging requires an extra step of
_pairing_ the device. In order to pair a new device, you can simply invoke `AdbConnectionManager.getInstance().pair(host, port, pairingCode)`.
After the pairing, you can connect to ADB via the usual `connect()` methods without any additional steps.

### Opening ADB Shell for Executing Arbitrary Commands
Simply use `AdbConnectionManager.getInstance().openStream("shell:")`. This will return an `AdbStream` which can be used
to read/write to the ADB shell via `AdbStream#openInputStream()` and `AdbStream#openOutputStream()` methods
respectively like a normal Java `Process`. While it is possible to read/write in the same thread (first write and then
read), this is not recommended because the shell might be stuck indefinitely for commands such as `top`.

**NOTE:** If you want to create a full-featured terminal emulator, this approach isn't recommended. Instead, you should
create a remote service via `app_process` or start an SSH server and connect to it.

## For Java (non-Android) Projects
It is possible to modify this library to work on non-Android project. But it isn't supported because Spake2-Java only
provides stable releases for Android. However, you can incorporate this library in your project by manually compiling
Spake2 library for your platforms.

## Contributing
By contributing to this project, you permit your work to be released under the terms of GNU General Public License, 
Version 3 or later **or** Apache License, Version 2.0.

### Donating
Bitcoin: `3FHTxPoYa92dNJK6pkhwyVkMG8Vv3VpGpg`

Ethereum: `0xa048a882301d9503d8c27Da8044c4E72dF14C817`

Open Collective: https://opencollective.com/muntashir

## License
Copyright 2021 Muntashir Al-Islam

Dual licensed under the terms of [GPL-3.0-or-later](https://www.gnu.org/licenses/gpl-3.0.html) or
[Apache-2.0 license](https://www.apache.org/licenses/LICENSE-2.0.html). Use whatever license you need for your project.

_Note regarding the Apache-2.0 license, this library has an LGPL dependency which may go against the policy of some
organizations such as ASF._
