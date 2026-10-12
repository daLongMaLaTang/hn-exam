package com.huaneng.shanyin.hnexam.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

/** MySQL stores ciphertext; server backups must also preserve the separate master key. */
@Service
public class AiKeyCipher {
    private final Path masterFile;
    private final SecureRandom random = new SecureRandom();
    public AiKeyCipher(@Value("${app.ai.master-key-file:../.local/ai-master.key}") String path) {
        masterFile = Path.of(path).toAbsolutePath().normalize();
    }
    private synchronized byte[] master(boolean create) throws Exception {
        if (!Files.exists(masterFile) && create) {
            Files.createDirectories(masterFile.getParent());
            byte[] key = new byte[32]; random.nextBytes(key);
            try {
                if (Files.getFileStore(masterFile.getParent()).supportsFileAttributeView("posix")) {
                    Files.createFile(masterFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } else {
                    Files.createFile(masterFile);
                    var acl = Files.getFileAttributeView(masterFile, AclFileAttributeView.class);
                    if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(masterFile)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
                }
                Files.write(masterFile, key, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) { }
        }
        byte[] key = Files.readAllBytes(masterFile);
        if (key.length != 32) throw new IllegalStateException();
        return key;
    }
    public String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master(true), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception e) { throw new IllegalStateException("密钥无法安全保存，请检查服务器密钥文件权限后重试"); }
    }
    public String decrypt(String value) {
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(value.substring(3));
            if (bytes.length < 29) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(master(false), "AES"), new GCMParameterSpec(128, bytes, 0, 12));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("已保存的AI密钥无法读取，请由admin重新接入或恢复服务器密钥文件"); }
    }
}
