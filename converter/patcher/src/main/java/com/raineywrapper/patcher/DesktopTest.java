package com.raineywrapper.patcher;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Exercises ApkPatcher on a desktop JVM, so patching can be tested without the Android app:
 *
 *   gradlew :patcher:run --args="template.apk out.apk keystore storePass alias packageName label core system romFile"
 */
public final class DesktopTest {
    public static void main(String[] args) throws Exception {
        File template = new File(args[0]);
        File out = new File(args[1]);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(args[2])) {
            ks.load(in, args[3].toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(args[4], args[3].toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain(args[4])) certs.add((X509Certificate) c);

        // A ROM path starting with /storage/ is a file already on the device: build a linked game.
        boolean linked = args[9].startsWith("/storage/");
        File rom = new File(args[9]);
        String ext = rom.getName().substring(rom.getName().lastIndexOf('.') + 1).toLowerCase();
        // Optional extra args: system/<path>=<file> to bundle BIOS files.
        StringBuilder systemFiles = new StringBuilder();
        List<String[]> extras = new ArrayList<>();
        for (int i = 10; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            extras.add(kv);
            systemFiles.append(systemFiles.length() == 0 ? "" : ",").append("\"").append(kv[0].substring("system/".length())).append("\"");
        }
        String json = linked
                ? "{\"title\":\"" + args[6] + "\",\"system\":\"" + args[8] + "\",\"core\":\"" + args[7]
                    + "\",\"rom\":\"" + args[9] + "\",\"linked\":true,\"files\":[],\"systemFiles\":[" + systemFiles + "]}"
                : "{\"title\":\"" + args[6] + "\",\"system\":\"" + args[8] + "\",\"core\":\"" + args[7]
                    + "\",\"rom\":\"rom." + ext + "\",\"files\":[\"rom." + ext + "\"],\"totalBytes\":" + rom.length()
                    + ",\"systemFiles\":[" + systemFiles + "]}";

        ApkPatcher.Spec spec = new ApkPatcher.Spec();
        spec.templatePackage = "com.raineywrapper.template";
        spec.packageName = args[5];
        spec.label = args[6];
        spec.versionCode = (int) (System.currentTimeMillis() / 60000);
        spec.versionName = "test";
        spec.keepCore = args[7];
        spec.assets.add(ApkPatcher.Asset.ofBytes("game.json", json.getBytes(StandardCharsets.UTF_8)));
        if (!linked) spec.assets.add(new ApkPatcher.Asset("rom." + ext, rom.length(), true, () -> new FileInputStream(rom)));
        for (String[] kv : extras) {
            File f = new File(kv[1]);
            spec.assets.add(new ApkPatcher.Asset(kv[0], f.length(), true, () -> new FileInputStream(f)));
        }

        File unsigned = new File(out.getPath() + ".unsigned");
        ApkPatcher.patch(template, unsigned, spec, (done, total) -> System.out.printf("packing %d/%d%n", done, total));
        ApkPatcher.sign(unsigned, out, key, certs);
        unsigned.delete();
        System.out.println("wrote " + out);
    }
}
