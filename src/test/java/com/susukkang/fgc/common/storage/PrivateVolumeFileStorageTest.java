package com.susukkang.fgc.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrivateVolumeFileStorageTest {
    @TempDir Path temp;

    @Test void persistsAcrossStorageInstancesWithoutExposingOriginalName() throws Exception {
        var properties = new FileStorageProperties(temp.resolve("volume"), 1024);
        var first = new PrivateVolumeFileStorage(properties);
        String key = first.store(new byte[]{1, 2, 3});
        assertThat(key).matches("[a-f0-9]{32}");
        try (var stream = new PrivateVolumeFileStorage(properties).open(key)) {
            assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
        }
        assertThat(first.delete(key)).isTrue();
        assertThat(first.delete(key)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"../outside", "/etc/passwd", "C:\\secret", "", "a/b"})
    void rejectsArbitraryStorageKeys(String key) {
        var storage = new PrivateVolumeFileStorage(new FileStorageProperties(temp.resolve("volume"), 1024));
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IOException.class);
    }

    @Test void ownerOnlyNonExecutableOnPosix() throws Exception {
        assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
        var storage = new PrivateVolumeFileStorage(new FileStorageProperties(temp.resolve("volume"), 1024));
        String key = storage.store(new byte[]{1});
        assertThat(Files.getPosixFilePermissions(temp.resolve("volume").resolve(key)))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test void refusesSymbolicLinkWithoutDeletingTarget() throws Exception {
        // Windows 일반 계정의 symlink 생성은 권한이 필요하므로 POSIX CI에서 반드시 검증한다.
        assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
        Path outside = Files.write(temp.resolve("outside"), new byte[]{9});
        Path root = Files.createDirectory(temp.resolve("volume"));
        String key = "a".repeat(32);
        Files.createSymbolicLink(root.resolve(key), outside);
        var storage = new PrivateVolumeFileStorage(new FileStorageProperties(root, 1024));
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(outside)).containsExactly(9);
    }

    @Test void ownerOnlyNonExecutableOnWindowsAcl() throws Exception {
        assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("acl"));
        var root = temp.resolve("volume");
        var storage = new PrivateVolumeFileStorage(new FileStorageProperties(root, 1024));
        String key = storage.store(new byte[]{1});
        var acl = Files.getFileAttributeView(root.resolve(key), java.nio.file.attribute.AclFileAttributeView.class).getAcl();
        assertThat(acl).hasSize(1);
        assertThat(acl.getFirst().principal()).isEqualTo(Files.getOwner(root));
        assertThat(acl.getFirst().permissions()).doesNotContain(java.nio.file.attribute.AclEntryPermission.EXECUTE);
    }
}
