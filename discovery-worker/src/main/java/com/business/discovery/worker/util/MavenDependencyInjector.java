package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses "package X does not exist" errors from mvn compile output, maps the
 * missing package prefix to a known Maven coordinate, and injects the dependency
 * into pom.xml before the ErrorFixAgent loop runs.
 *
 * ErrorFixAgent can only edit .java files — it cannot fix a missing classpath dep.
 * Injecting deps here gives ErrorFixAgent a clean compile baseline to work from.
 */
@Slf4j
public final class MavenDependencyInjector {

    private MavenDependencyInjector() {}

    private static final Pattern MISSING_PACKAGE =
            Pattern.compile("package ([\\w.]+) does not exist");

    // Longest-prefix match: package prefix → Maven dependency XML block
    private static final Map<String, String> KNOWN_DEPS = new LinkedHashMap<>();

    static {
        dep("org.modelmapper",         "org.modelmapper",     "modelmapper",         "3.2.0",       null);
        dep("com.razorpay",            "com.razorpay",        "razorpay-java",        "1.4.7",       null);
        dep("org.json",                "org.json",            "json",                 "20240303",    null);
        dep("com.cloudinary",          "com.cloudinary",      "cloudinary-http44",    "1.38.0",      null);
        dep("com.twilio",              "com.twilio.sdk",      "twilio",               "10.1.5",      null);
        dep("com.sendgrid",            "com.sendgrid",        "sendgrid-java",        "4.10.2",      null);
        dep("com.stripe",              "com.stripe",          "stripe-java",          "24.5.0",      null);
        dep("com.auth0",               "com.auth0",           "java-jwt",             "4.4.0",       null);
        dep("org.apache.commons.lang3","org.apache.commons",  "commons-lang3",        "3.14.0",      null);
        dep("org.apache.commons.codec","commons-codec",       "commons-codec",        "1.17.0",      null);
        dep("org.apache.commons.io",   "commons-io",          "commons-io",           "2.16.1",      null);
        dep("com.google.firebase",     "com.google.firebase", "firebase-admin",       "9.2.0",       null);
        dep("com.google.zxing",        "com.google.zxing",    "core",                 "3.5.3",       null);
        dep("com.opencsv",             "com.opencsv",         "opencsv",              "5.9",         null);
        dep("org.mapstruct",           "org.mapstruct",       "mapstruct",            "1.5.5.Final", null);
        dep("com.itextpdf",            "com.itextpdf",        "itext7-core",          "7.2.5",       null);
        dep("com.amazonaws",           "com.amazonaws",       "aws-java-sdk-s3",      "1.12.720",    null);
        dep("software.amazon.awssdk",  "software.amazon.awssdk", "s3",               "2.25.60",     null);
    }

    /**
     * Parses compile errors, injects any recognized missing dependencies into pom.xml.
     * Returns true if at least one dependency was injected (caller should re-compile).
     */
    public static boolean injectFromCompileErrors(Path pomPath, String compileOutput) {
        Set<String> missingPackages = extractMissingPackages(compileOutput);
        if (missingPackages.isEmpty()) return false;

        if (!Files.exists(pomPath)) {
            log.warn("[MavenDependencyInjector] pom.xml not found at {}", pomPath);
            return false;
        }

        try {
            String pom = Files.readString(pomPath);
            StringBuilder toInject = new StringBuilder();

            for (String pkg : missingPackages) {
                String depXml = findDep(pkg);
                if (depXml == null) {
                    log.warn("[MavenDependencyInjector] No known Maven coordinate for package: {}", pkg);
                    continue;
                }
                String artifactId = extractArtifactId(depXml);
                if (pom.contains("<artifactId>" + artifactId + "</artifactId>")
                        || toInject.toString().contains("<artifactId>" + artifactId + "</artifactId>")) {
                    log.info("[MavenDependencyInjector] {} already in pom.xml — skipping", artifactId);
                    continue;
                }
                toInject.append(depXml);
                log.info("[MavenDependencyInjector] Injecting dep for missing package '{}': {}", pkg, artifactId);
            }

            if (toInject.isEmpty()) return false;

            String patched = pom.replace("</dependencies>", toInject + "\t</dependencies>");
            Files.writeString(pomPath, patched);
            return true;

        } catch (IOException e) {
            log.warn("[MavenDependencyInjector] Failed to patch pom.xml: {}", e.getMessage());
            return false;
        }
    }

    private static Set<String> extractMissingPackages(String output) {
        Set<String> packages = new LinkedHashSet<>();
        if (output == null) return packages;
        Matcher m = MISSING_PACKAGE.matcher(output);
        while (m.find()) packages.add(m.group(1));
        return packages;
    }

    // Longest-prefix match so "org.apache.commons.lang3" wins over "org.apache.commons"
    private static String findDep(String missingPackage) {
        String best = null;
        for (String prefix : KNOWN_DEPS.keySet()) {
            if (missingPackage.startsWith(prefix)) {
                if (best == null || prefix.length() > best.length()) best = prefix;
            }
        }
        return best != null ? KNOWN_DEPS.get(best) : null;
    }

    private static String extractArtifactId(String depXml) {
        int start = depXml.indexOf("<artifactId>") + "<artifactId>".length();
        int end   = depXml.indexOf("</artifactId>", start);
        return depXml.substring(start, end);
    }

    private static void dep(String packagePrefix,
                            String groupId, String artifactId, String version, String scope) {
        StringBuilder xml = new StringBuilder();
        xml.append("\t\t<dependency>\n")
           .append("\t\t\t<groupId>").append(groupId).append("</groupId>\n")
           .append("\t\t\t<artifactId>").append(artifactId).append("</artifactId>\n")
           .append("\t\t\t<version>").append(version).append("</version>\n");
        if (scope != null && !scope.isBlank())
            xml.append("\t\t\t<scope>").append(scope).append("</scope>\n");
        xml.append("\t\t</dependency>\n");
        KNOWN_DEPS.put(packagePrefix, xml.toString());
    }
}
