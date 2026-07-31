package io.agentscope.dataagent.workspace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedSandboxFilesystemMirrorTest {

    @TempDir Path tempDir;

    @Test
    void replacementRemovesFilesDeletedFromSandboxSnapshot() throws Exception {
        Path target = tempDir.resolve("mirror");
        Files.createDirectories(target.resolve("reports"));
        Files.writeString(target.resolve("reports/obsolete.md"), "old");
        Files.writeString(target.resolve("keep.md"), "old value");

        Path staging = tempDir.resolve(".mirror.stage-test");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("keep.md"), "new value");
        Files.writeString(staging.resolve("created.md"), "new");

        SharedSandboxFilesystem.replaceMirrorDirectory(staging, target);

        assertThat(target.resolve("reports/obsolete.md")).doesNotExist();
        assertThat(target.resolve("keep.md")).hasContent("new value");
        assertThat(target.resolve("created.md")).hasContent("new");
        assertThat(staging).doesNotExist();
    }

    @Test
    void tarExtractionStripsLeadingDotSlashAndLandsUnderStaging() throws Exception {
        Path staging = tempDir.resolve(".mirror.stage-tar");
        Files.createDirectories(staging);

        byte[] tarBytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                TarArchiveOutputStream tar = new TarArchiveOutputStream(buffer)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            addFile(tar, "./README.md", "# harness");
            addFile(tar, "./knowledge/notes.txt", "hello");
            tar.finish();
            tarBytes = buffer.toByteArray();
        }

        SharedSandboxFilesystem.extractTarToStaging(new ByteArrayInputStream(tarBytes), staging);

        assertThat(staging.resolve("README.md")).hasContent("# harness");
        assertThat(staging.resolve("knowledge/notes.txt")).hasContent("hello");
        // Files should land directly under staging; no leftover "./" prefix should remain.
        assertThat(java.util.Arrays.stream(staging.toFile().list())
                        .filter(name -> !name.equals("README.md")
                                && !name.equals("knowledge"))
                        .filter(name -> name.startsWith("."))
                        .count())
                .isZero();
    }

    @Test
    void tarExtractionRejectsEntriesThatEscapeStaging() throws Exception {
        Path staging = tempDir.resolve(".mirror.stage-escape");
        Files.createDirectories(staging);

        byte[] tarBytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                TarArchiveOutputStream tar = new TarArchiveOutputStream(buffer)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            addFile(tar, "../evil.txt", "should never land");
            addFile(tar, "./safe.txt", "ok");
            tar.finish();
            tarBytes = buffer.toByteArray();
        }

        SharedSandboxFilesystem.extractTarToStaging(new ByteArrayInputStream(tarBytes), staging);

        assertThat(staging.resolve("safe.txt")).hasContent("ok");
        assertThat(staging.resolve("evil.txt")).doesNotExist();
        assertThat(tempDir.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void tarExtractionStripsWindowsDriveLetterPrefix() throws Exception {
        // A bind mount whose container target carries a Windows-style host path
        // (e.g. `E:/myskills/...`) ends up as a literal `E:/` directory inside the
        // sandbox workspace. The tar stream surfaces those entries as `./E:/...`,
        // which on Windows would otherwise be parsed as absolute paths and skip past
        // the staging check.
        Path staging = tempDir.resolve(".mirror.stage-windowspath");
        Files.createDirectories(staging);

        byte[] tarBytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                TarArchiveOutputStream tar = new TarArchiveOutputStream(buffer)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            addFile(tar, "./E:/myskills/investment/.keep", "mounted");
            tar.finish();
            tarBytes = buffer.toByteArray();
        }

        SharedSandboxFilesystem.extractTarToStaging(new ByteArrayInputStream(tarBytes), staging);

        // After stripping the drive letter, the file lands under staging directly
        // (so `<staging>/myskills/investment/.keep`); on Windows it could also be
        // written into a literal sibling dir like `<staging>/E:/...` if the OS
        // tolerates the colon. Either way it must be inside staging.
        Path direct = staging.resolve("myskills").resolve("investment").resolve(".keep");
        Path driveRoot = staging.resolve("E:").resolve("myskills").resolve("investment").resolve(".keep");
        assertThat(java.util.Arrays.asList(direct, driveRoot))
                .anyMatch(p -> {
                    try {
                        return Files.exists(p) && "mounted".equals(Files.readString(p));
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    private static void addFile(TarArchiveOutputStream tar, String name, String content)
            throws java.io.IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        byte[] body = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        entry.setSize(body.length);
        tar.putArchiveEntry(entry);
        tar.write(body);
        tar.closeArchiveEntry();
    }
}
