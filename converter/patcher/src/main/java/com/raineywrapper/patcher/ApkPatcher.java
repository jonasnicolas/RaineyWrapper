package com.raineywrapper.patcher;

import com.android.apksig.ApkSigner;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Turns the generic player APK into a game APK without a build system:
 * renames the package, sets the label and version, swaps launcher icons,
 * drops unused emulator cores, adds the game under assets/game/, then signs.
 *
 * Pure Java so the same code runs inside the Android converter app and on a desktop JVM.
 */
public final class ApkPatcher {

    private ApkPatcher() {}

    public interface Opener {
        InputStream open() throws IOException;
    }

    /** A file to place under assets/game/. */
    public static final class Asset {
        final String name;
        final long size;
        final boolean compress;
        final Opener opener;

        /** @param compress false for data that's already compressed (.chd, .pbp, .zip) */
        public Asset(String name, long size, boolean compress, Opener opener) {
            this.name = name;
            this.size = size;
            this.compress = compress;
            this.opener = opener;
        }

        public String name() {
            return name;
        }

        public long size() {
            return size;
        }

        public static Asset ofBytes(String name, byte[] data) {
            return new Asset(name, data.length, true, () -> new java.io.ByteArrayInputStream(data));
        }
    }

    public static final class Spec {
        public String templatePackage;
        public String packageName;
        public String label;
        public int versionCode;
        public String versionName;
        /** File name of the core to keep, e.g. "libmgba_libretro_android.so"; others are dropped. */
        public String keepCore;
        /** Zip entries to overwrite, e.g. "res/mipmap-xxhdpi-v4/ic_launcher_foreground.png". */
        public final Map<String, byte[]> replace = new LinkedHashMap<>();
        public final List<Asset> assets = new ArrayList<>();
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final String CORE_SUFFIX = "_libretro_android.so";
    private static final String GAME_DIR = "assets/game/";
    /** Per-core support files: assets/coredata/<core>/..., kept only for the core in use. */
    private static final String CORE_DATA_DIR = "assets/coredata/";

    public static void patch(File template, File out, Spec spec, Progress progress) throws IOException {
        long total = 0;
        for (Asset a : spec.assets) total += a.size;
        long[] done = {0};

        try (ZipFile in = new ZipFile(template);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            byte[] buffer = new byte[1 << 16];
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (shouldDrop(name, spec)) continue;

                byte[] replacement;
                if (name.equals("AndroidManifest.xml")) {
                    try (InputStream s = in.getInputStream(entry)) {
                        replacement = patchManifest(s, spec);
                    }
                } else {
                    replacement = spec.replace.get(name);
                }

                zip.setLevel(Deflater.DEFAULT_COMPRESSION);
                if (replacement != null) {
                    putBytes(zip, name, replacement, entry.getMethod() == ZipEntry.DEFLATED);
                } else {
                    ZipEntry copy = new ZipEntry(name);
                    copy.setMethod(entry.getMethod());
                    if (entry.getMethod() == ZipEntry.STORED) {
                        copy.setSize(entry.getSize());
                        copy.setCompressedSize(entry.getSize());
                        copy.setCrc(entry.getCrc());
                    }
                    zip.putNextEntry(copy);
                    try (InputStream s = in.getInputStream(entry)) {
                        copyStream(s, zip, buffer, null, null);
                    }
                    zip.closeEntry();
                }
            }

            // Games are large; favour speed over a few percent of size.
            zip.setLevel(Deflater.BEST_SPEED);
            for (Asset asset : spec.assets) {
                String name = GAME_DIR + asset.name;
                ZipEntry entry = new ZipEntry(name);
                if (asset.compress) {
                    entry.setMethod(ZipEntry.DEFLATED);
                } else {
                    // STORED entries need size and CRC up front, which means reading the file twice.
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(asset.size);
                    entry.setCompressedSize(asset.size);
                    entry.setCrc(crcOf(asset, buffer));
                }
                zip.putNextEntry(entry);
                try (InputStream s = asset.opener.open()) {
                    final long totalBytes = total;
                    copyStream(s, zip, buffer, done, n -> {
                        if (progress != null) progress.onProgress(n, totalBytes);
                    });
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean shouldDrop(String name, Spec spec) {
        if (name.startsWith(GAME_DIR)) return true;
        if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
                || name.endsWith(".EC") || name.endsWith(".DSA") || name.equals("META-INF/MANIFEST.MF"))) {
            return true;
        }
        if (name.startsWith("lib/") && name.endsWith(CORE_SUFFIX)) {
            return spec.keepCore == null || !name.endsWith("/" + spec.keepCore);
        }
        if (name.startsWith(CORE_DATA_DIR)) {
            return spec.keepCore == null || !name.startsWith(CORE_DATA_DIR + coreName(spec.keepCore) + "/");
        }
        return false;
    }

    /** "libppsspp_libretro_android.so" -> "ppsspp" */
    private static String coreName(String library) {
        return library.substring(3, library.length() - CORE_SUFFIX.length());
    }

    static byte[] patchManifest(InputStream manifestXml, Spec spec) throws IOException {
        AndroidManifestBlock manifest = AndroidManifestBlock.load(manifestXml);
        manifest.setPackageName(spec.packageName);
        manifest.setVersionCode(spec.versionCode);
        manifest.setVersionName(spec.versionName);
        manifest.setApplicationLabel(spec.label);

        // Values derived from the package name (provider authorities, custom permissions)
        // must be unique per app, or a second game would fail to install.
        Iterator<ResXmlElement> elements = manifest.recursiveElements();
        while (elements.hasNext()) {
            Iterator<ResXmlAttribute> attrs = elements.next().getAttributes();
            while (attrs.hasNext()) {
                ResXmlAttribute attr = attrs.next();
                if (attr.getValueType() != ValueType.STRING) continue;
                String value = attr.getValueAsString();
                if (value != null && value.startsWith(spec.templatePackage + ".")) {
                    attr.setValueAsString(spec.packageName + value.substring(spec.templatePackage.length()));
                }
            }
        }
        manifest.refreshFull();
        return manifest.getBytes();
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data, boolean deflate) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (deflate) {
            entry.setMethod(ZipEntry.DEFLATED);
        } else {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static long crcOf(Asset asset, byte[] buffer) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream s = asset.opener.open()) {
            int n;
            while ((n = s.read(buffer)) > 0) crc.update(buffer, 0, n);
        }
        return crc.getValue();
    }

    private interface LongConsumer {
        void accept(long value);
    }

    private static void copyStream(InputStream in, OutputStream out, byte[] buffer, long[] counter, LongConsumer onProgress)
            throws IOException {
        int n;
        long lastReport = 0;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (counter != null) {
                counter[0] += n;
                if (onProgress != null && counter[0] - lastReport > (4 << 20)) {
                    lastReport = counter[0];
                    onProgress.accept(counter[0]);
                }
            }
        }
        if (counter != null && onProgress != null) onProgress.accept(counter[0]);
    }

    /** Signs with APK Signature Scheme v2 + v3 (minSdk 26 doesn't need v1/JAR signing). */
    public static void sign(File unsigned, File out, PrivateKey key, List<X509Certificate> certs) throws Exception {
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("raineywrapper", key, certs).build();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(unsigned)
                .setOutputApk(out)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }
}
