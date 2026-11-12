package dev.regnum.economy.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Runs before Nations entrypoint classes can link; no Minecraft or client class is loaded here. */
public final class NationsCompatibility implements PreLaunchEntrypoint {
    private static final String COMMIT = "483330f8523f38c4bc13ae68e43335921dc8dc90";

    @Override
    public void onPreLaunch() {
        String guidance = "Regnum Economy requires Nations PR #20 at " + COMMIT
                + ". Install the verified Nations JAR used to build Economy (see README.md); do not use a different 0.9.2 build.";
        try {
            Properties lock = new Properties();
            try (var input = getClass().getResourceAsStream("/regnum-economy-nations.properties")) {
                if (input == null) throw new IOException("Missing packaged Nations compatibility lock");
                lock.load(input);
            }
            if (!COMMIT.equals(lock.getProperty("commit"))) throw new IOException("Incorrect Nations commit lock");
            var loader = FabricLoader.getInstance();
            var nations = loader.getModContainer("regnum-nations")
                    .orElseThrow(() -> new IllegalStateException(guidance));
            Path artifact;
            if (loader.isDevelopmentEnvironment()) {
                String source = System.getProperty("regnumEconomy.nationsSourceJar");
                if (source == null || source.isBlank()) throw new IOException("Launch development runs through the configured Loom task");
                artifact = Path.of(source);
            } else {
                var origin = nations.getOrigin();
                if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) {
                    throw new IOException("Install Nations as a separate packaged JAR");
                }
                artifact = origin.getPaths().getFirst();
            }
            if (!Files.isRegularFile(artifact) || !fingerprint(artifact).equals(lock.getProperty("sha256"))) {
                throw new IOException("The installed Nations artifact does not match the pinned build");
            }
        } catch (IOException | NoSuchAlgorithmException | RuntimeException failure) {
            throw new IllegalStateException(guidance + " Cause: " + failure.getMessage(), failure);
        }
    }

    private static String fingerprint(Path artifact) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            var entries = Collections.list(archive.entries()).stream().filter(entry -> !entry.isDirectory())
                    .sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            var names = new HashSet<String>();
            for (var entry : entries) {
                if (!names.add(entry.getName())) throw new IOException("Duplicate Nations archive entry");
                byte[] name = entry.getName().getBytes(StandardCharsets.UTF_8);
                byte[] content;
                try (var input = archive.getInputStream(entry)) { content = input.readAllBytes(); }
                digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(8).putLong(content.length).array());
                digest.update(content);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
