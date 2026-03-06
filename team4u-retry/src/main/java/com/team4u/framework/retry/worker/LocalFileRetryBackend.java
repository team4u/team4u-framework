package com.team4u.framework.retry.worker;

import cn.hutool.json.JSONUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地文件版 RetryBackend，适合单机持久化场景。
 */
public class LocalFileRetryBackend extends AbstractQueueingRetryBackend {

    private final Path storageFile;

    public LocalFileRetryBackend() {
        this(Paths.get("BackEndretry.txt"), 30_000L);
    }

    public LocalFileRetryBackend(Path storageFile) {
        this(storageFile, 30_000L);
    }

    public LocalFileRetryBackend(Path storageFile, long pendingRecoverAfterMillis) {
        super(pendingRecoverAfterMillis);
        this.storageFile = storageFile;
        initFile();
        loadFromDisk();
    }

    private void initFile() {
        try {
            Path parent = storageFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(storageFile)) {
                Files.createFile(storageFile);
                Files.write(storageFile, "[]".getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("初始化本地重试文件失败: " + storageFile, e);
        }
    }

    private synchronized void loadFromDisk() {
        try {
            byte[] bytes = Files.readAllBytes(storageFile);
            if (bytes.length == 0) {
                return;
            }

            String json = new String(bytes, StandardCharsets.UTF_8).trim();
            if (json.isEmpty() || "[]".equals(json)) {
                return;
            }

            List<RetryTaskRecord> list = JSONUtil.parseArray(json).toList(RetryTaskRecord.class);
            restoreRecords(list);
        } catch (Exception e) {
            throw new IllegalStateException("加载本地重试文件失败: " + storageFile, e);
        }
    }

    @Override
    protected void afterStateChange() {
        persistToDisk();
    }

    private synchronized void persistToDisk() {
        try {
            List<RetryTaskRecord> snapshot = new ArrayList<RetryTaskRecord>();
            snapshot.addAll(recordCopies());

            String json = JSONUtil.toJsonStr(snapshot);
            Path tmp = Paths.get(storageFile.toString() + ".tmp");

            Files.write(tmp,
                    json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            try {
                Files.move(tmp, storageFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("持久化本地重试文件失败: " + storageFile, e);
        }
    }
}
