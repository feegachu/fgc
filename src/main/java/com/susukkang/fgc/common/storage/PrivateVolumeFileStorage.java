package com.susukkang.fgc.common.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** 신뢰된 단일 앱 프로세스 소유의 비공개 볼륨. 업로드 이름은 경로로 사용하지 않는다. */
@Component
@EnableConfigurationProperties(FileStorageProperties.class)
public class PrivateVolumeFileStorage implements FileStorage {
    private final Path root;
    private final long maxBytes;

    public PrivateVolumeFileStorage(FileStorageProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
        this.maxBytes = properties.maxFileBytes();
        if (root.getParent() == null || root.equals(Path.of("").toAbsolutePath().normalize())
                || root.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("전용 하위 디렉터리를 저장소로 지정해야 합니다");
        }
    }

    private void prepareRoot() throws IOException {
        // 심볼릭 링크를 따라 다른 볼륨·웹 루트로 저장하거나 삭제하지 않는다.
        for (Path current = root; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("저장소 링크 경로는 허용하지 않습니다");
        }
        Files.createDirectories(root);
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        } else {
            var acl = Files.getFileAttributeView(root, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("소유자 전용 권한을 지원하는 저장소가 필요합니다");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }

    private Path resolve(String key) throws IOException {
        if (key == null || !key.matches("[a-f0-9]{32}")) throw new IOException("잘못된 저장키입니다");
        prepareRoot();
        Path path = root.resolve(key);
        if (Files.isSymbolicLink(path)) throw new IOException("저장 파일 링크는 허용하지 않습니다");
        return path;
    }

    @Override
    public String store(byte[] content) throws IOException {
        if (content.length == 0 || content.length > maxBytes) throw new IOException("파일 크기 제한 초과");
        String key = UUID.randomUUID().toString().replace("-", "");
        Path file = resolve(key);
        // 생성 시점부터 소유자 읽기/쓰기만 허용한다. 배포 볼륨에는 noexec도 적용한다.
        FileAttribute<?>[] attributes;
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            attributes = new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
        } else {
            var owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
            var permissions = EnumSet.allOf(AclEntryPermission.class);
            permissions.remove(AclEntryPermission.EXECUTE);
            var acl = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(permissions).build());
            attributes = new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return acl; }
            }};
        }
        boolean created = false;
        try (var out = Files.newByteChannel(file,
                java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), attributes)) {
            created = true;
            var buffer = java.nio.ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) out.write(buffer);
        } catch (IOException ex) {
            if (created) Files.deleteIfExists(file);
            throw ex;
        }
        return key;
    }

    @Override
    public InputStream open(String key) throws IOException {
        Path file = resolve(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.NoSuchFileException(key);
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean delete(String key) throws IOException {
        Path file = resolve(key);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("일반 파일만 삭제할 수 있습니다");
        }
        return Files.deleteIfExists(file);
    }
}
