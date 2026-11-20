/*
 * Copyright (C) 2026 flashsky1024
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.flashsky1024.tavernium;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把运行日志与崩溃记录打成一个 zip。
 *
 * <p>之前逐个文件往下载目录写，一旦某个扩展名被 MediaStore 拒绝（{@code .log} 就是
 * 其中之一），用户只能看到崩溃记录，运行日志静默消失。打成一个 {@code .zip}
 * 后只有一次写入，MIME 明确，也不会出现"导出了几个文件"对不上号的情况。</p>
 */
final class LogBundle {
    /** 单个压缩包上限：日志本身已滚动限制在 4.5MB，这里只是兜底，防止异常膨胀。 */
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    private LogBundle() {
    }

    /**
     * 生成压缩包，返回临时文件；调用方负责用完后删除。
     *
     * @param dir      临时目录，不存在会被创建
     * @param files    待打包文件，按给定顺序写入
     * @param manifest 包内 README.txt 的内容，可为 null
     */
    static File build(File dir, List<File> files, String manifest) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IOException("没有可打包的日志");
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建临时目录");
        }
        File out = new File(dir, "tavernium-logs.zip");
        long total = 0L;
        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(new FileOutputStream(out));
            if (manifest != null && !manifest.isEmpty()) {
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write(manifest.getBytes("UTF-8"));
                zip.closeEntry();
            }
            Set<String> used = new HashSet<String>();
            int written = 0;
            byte[] buf = new byte[16384];
            for (File f : files) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                total += f.length();
                if (total > MAX_TOTAL_BYTES) {
                    throw new IOException("日志总量超过 32MB");
                }
                String entry = uniqueName(used, f.getName());
                zip.putNextEntry(new ZipEntry(entry));
                InputStream in = new FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        zip.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                zip.closeEntry();
                written++;
            }
            if (written == 0) {
                // 文件列表非空但一个都读不到：不能返回一个只有 README 的空包，
                // 那会让用户以为日志已经导出成功。
                throw new IOException("没有可读取的日志文件");
            }
            return out;
        } catch (IOException e) {
            out.delete();
            throw e;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 同名条目加序号：轮转文件与崩溃文件都可能在极端情况下重名，zip 不允许重复。 */
    private static String uniqueName(Set<String> used, String name) {
        if (name == null || name.isEmpty()) {
            name = "log.txt";
        }
        String candidate = name;
        int i = 1;
        while (!used.add(candidate)) {
            int dot = name.lastIndexOf('.');
            candidate = dot > 0
                    ? name.substring(0, dot) + "-" + i + name.substring(dot)
                    : name + "-" + i;
            i++;
        }
        return candidate;
    }
}
