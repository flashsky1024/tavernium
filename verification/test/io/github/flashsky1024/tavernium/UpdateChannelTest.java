package io.github.flashsky1024.tavernium;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

/**
 * 正式版 / 调试版互相安装的信任规则测试。
 *
 * 两个包名不同，Android 会并行安装而不是覆盖。证书必须匹配各自固定的
 * 公开指纹，不能因为包名是“另一个渠道”就接受任意签名。
 */
public final class UpdateChannelTest {
    private static int passed;
    private static int failed;

    private static final String RELEASE_CERT =
            "MIIFgTCCA2mgAwIBAgIINWwD001mzCgwDQYJKoZIhvcNAQEMBQAwbjELMAkGA1UE"
            + "BhMCQ04xEDAOBgNVBAgTB0JlaWppbmcxEDAOBgNVBAcTB0JlaWppbmcxFTATBgNV"
            + "BAoTDGZsYXNoc2t5MTAyNDEQMA4GA1UECxMHQW5kcm9pZDESMBAGA1UEAxMJVGF2"
            + "ZXJuaXVtMCAXDTI2MTAyNDIyMjM1NFoYDzIwNTQwMzExMjIyMzU0WjBuMQswCQYD"
            + "VQQGEwJDTjEQMA4GA1UECBMHQmVpamluZzEQMA4GA1UEBxMHQmVpamluZzEVMBMG"
            + "A1UEChMMZmxhc2hza3kxMDI0MRAwDgYDVQQLEwdBbmRyb2lkMRIwEAYDVQQDEwlU"
            + "YXZlcm5pdW0wggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDb70NfMCFd"
            + "S1oYCQ0VpDKuIAqA2Ejyz5TPQmpVvwrDKCwVnEvLUgfrjoMZJXfMSjlaxFhYbUCP"
            + "HKygaWuLfH1Si+SKHqq40wl0FA2cthqotHNsXTpNYR45uXgcqIKP9DKL1AvoiBuI"
            + "hLHk+WKEtvEwg/0bIqut8SDhQ2PpGfKknCOpEBUREEmWBp6pCCd91nusbHOoY1ab"
            + "3EKimFXoW666EmT1nIyK5/u2kBNipshIsACxD9BRjij8koaZyHsxHBDkdV3/3kvs"
            + "Lg+PZNCjVZEN8cYAHIpgGKVue1Ks1NLfatoO3CncaExeRC9DNEce3UHJtl6Xp7Q2"
            + "0J2ufXcngDb1+F/dmNeuEOeqS4trzjxZqp7wZnpreADAa8c0mUJm6Us/k9Z3HjM5"
            + "i0CDOH7v2yF/Ap+iCnhnf2rq/Qyb+8LqusSdhLjZKfyeqJmkgZ4xMbaw229B1gME"
            + "/geXLwaG0pXqJdSCxEZtyAmx1RoXwyqq+YRgW1QOeUCDWuP35mUep4Y2wCAxVIac"
            + "dabxLFPYbySh6hairZwH/kFYS3ZIYELt8G+1l0Pf2+9KIrHauJMz/hT4NvSu4sKn"
            + "8o2i9Nf5z/5UXdCShHi2gS927R/wrjllXSlq9C7410IlS0152LBiyehXC1mEMSO3"
            + "K6KOERdjRU/CkeGK6IibpO87JHh2efu4SQIDAQABoyEwHzAdBgNVHQ4EFgQUCSU6"
            + "ezb/F8zjzIakTwionn13D2UwDQYJKoZIhvcNAQEMBQADggIBAFIXgNbcUsnasEoi"
            + "/WIpzB8jHbUw4D0g/SeM3n9DV9QELB7KPBNVCONVJ7ZvvdER/7g0OrKQDYH9jLcg"
            + "qC2yfedWyB32Bm6fkAJ4Jl94IRF7IKQVQn5HOGTITbIie8ZWbKWl+uE1cmSDMwkf"
            + "xIyZKBiX6oJN9hy37ObdIs4d1VkLXh1V/39qlSfjiBxy5ZkVy5otkK/fkIZbVT8A"
            + "HKgrGemQH8/LQcGoyxj5vLCRnFOuKnrLOyf4qc/VbPgk8pWyM/09eeinbxBapo8C"
            + "W+tvQ/rYKpaoduCRvStobcosjMIuNSP2f2H3KtjqklCDUDhZWhA36GE+n47sKuXw"
            + "W4HYQz7g08V5kju3h4L884ORFZa7TkD/HcqzkQhxm5RmiV48dIrEphX4mj24FbRU"
            + "sQJfNDq98F/4m0fsXB9qfeg7BgcAfmIuFIaKuy/S91ovYE7Qpv/gK+njQmEXfkQh"
            + "xTP+6y7Pry9UAHgbe9uBeCuZls2kTvbc3zql5f/N1t94IkoQKTCNPmINm2hRvVnB"
            + "e9QD6Lf109QQEWQzSn3tA+jJQzvu7OtqTLgjb/I3tF4WnGCbpn9dWp2qhNhyRydx"
            + "Phe47FlEJRlC1uBw4ztxaR5TK1Bg26iCL/jrO8rp9Y8zKZqXvtErIIF81fSSGUEn"
            + "ABuCZ2AuwiNKiDBD+CM4QEEdroxT";
    private static final String DEBUG_CERT =
            "MIIC5DCCAcwCAQEwDQYJKoZIhvcNAQELBQAwNzEWMBQGA1UEAwwNQW5kcm9pZCBE"
            + "ZWJ1ZzEQMA4GA1UECgwHQW5kcm9pZDELMAkGA1UEBhMCVVMwIBcNMjYxMDE2MDQz"
            + "NjAxWhgPMjA1NjEwMDgwNDM2MDFaMDcxFjAUBgNVBAMMDUFuZHJvaWQgRGVidWcx"
            + "EDAOBgNVBAoMB0FuZHJvaWQxCzAJBgNVBAYTAlVTMIIBIjANBgkqhkiG9w0BAQEF"
            + "AAOCAQ8AMIIBCgKCAQEAjKkoOGgBtD9qao307UAj58+23in+oyzm08b4t+X3irMR"
            + "0ZNsfwxOPCLaoGSvGHRh69CtPUGcR1CaZ/ZQMurPr1iVPkXNgl61A+rzVcQSswau"
            + "IrvNfQ6b5WwzxWiVgu18Nm+QqSuuUlyTKPbu4wr4icDABH5Ipytkvr9FppCfpQav"
            + "JT9xg6ecWNeMw5sWKl3yCqHmh3iULuSrEWGm4jiP+y+IWhLTWcRKcmyPhVFwbfna"
            + "tZh0B7at9KPYLb2srB/+nshM8ZwGoe4GlJFXPxPeRFUwkXqk1mIS5e812WUW4fKz"
            + "x2FXCBEdbqsAW2D1XKkhwTFY16TbdGQK7Oc5GeALBQIDAQABMA0GCSqGSIb3DQEB"
            + "CwUAA4IBAQB7C/pVczeaCHGBmQbfa7pk0V8p1qsjKDN+jSDkrbW5FtLlN0+SKfBo"
            + "e6+cJ4Y4J1RgsITSQuL4p/vNGIaVBQ7eqpIDDpUiaFfowbuqbme3uByTRHPHf8xD"
            + "c4ttTEP2q8s8hGURSDKiqJEYf5bWeVYTQ8aEZe31k1dhIecxqP2vwiinbDZvr2AZ"
            + "pg1y+peBFqTeGqw1SNUJQ6i037aOdjeVhWRVRIb6FWE+9IAueVxL9tZoTu0d3wmc"
            + "Ue4VsAu5K9QJhsMtq6Tta2lh2zPicgB/2QVbkl29CHauiYz3CUZ2KrLQkeDmlwO+"
            + "6KL9rpcM9j3TGNJwDJguxodCTXY8xkYk";

    private static final Signature RELEASE_SIG = signature(RELEASE_CERT);
    private static final Signature DEBUG_SIG = signature(DEBUG_CERT);

    public static void main(String[] args) throws Exception {
        testPackages();
        testPinnedSignatures();
        testCrossChannelTrust();
        System.out.println();
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void testPackages() {
        section("包名与渠道映射");
        eq("正式渠道包名", UpdateManager.PACKAGE_STABLE,
                UpdateManager.packageFor(UpdateManager.CHANNEL_STABLE));
        eq("调试渠道包名", UpdateManager.PACKAGE_DEBUG,
                UpdateManager.packageFor(UpdateManager.CHANNEL_DEBUG));
        check("正式版可安装调试版", UpdateManager.isSiblingPackage(
                UpdateManager.PACKAGE_STABLE, UpdateManager.PACKAGE_DEBUG));
        check("调试版可安装正式版", UpdateManager.isSiblingPackage(
                UpdateManager.PACKAGE_DEBUG, UpdateManager.PACKAGE_STABLE));
        check("同包名不算跨渠道", !UpdateManager.isSiblingPackage(
                UpdateManager.PACKAGE_STABLE, UpdateManager.PACKAGE_STABLE));
        check("未知包名不算跨渠道", !UpdateManager.isSiblingPackage(
                UpdateManager.PACKAGE_STABLE, "com.example.fake"));
        check("当前为正式版时另一渠道显示为跨应用", UpdateManager.isCrossChannel(
                new FakeContext(UpdateManager.PACKAGE_STABLE), UpdateManager.CHANNEL_DEBUG));
        check("当前为调试版时正式渠道显示为跨应用", UpdateManager.isCrossChannel(
                new FakeContext(UpdateManager.PACKAGE_DEBUG), UpdateManager.CHANNEL_STABLE));
        check("当前渠道不显示为跨应用", !UpdateManager.isCrossChannel(
                new FakeContext(UpdateManager.PACKAGE_STABLE), UpdateManager.CHANNEL_STABLE));
        UpdateManager.Release release = new UpdateManager.Release();
        release.versionName = "2.0.0";
        release.versionCode = 52;
        String crossLabel = release.label(52, true);
        check("跨渠道版本标记为独立应用", crossLabel.contains("独立应用"));
        check("跨渠道不误标当前版本", !crossLabel.contains("当前版本"));
    }

    private static void testPinnedSignatures() {
        section("固定证书指纹");
        check("正式证书匹配正式指纹", UpdateManager.matchesPinnedSigner(
                new Signature[]{RELEASE_SIG}, UpdateManager.SIGNER_SHA256_STABLE));
        check("调试证书匹配调试指纹", UpdateManager.matchesPinnedSigner(
                new Signature[]{DEBUG_SIG}, UpdateManager.SIGNER_SHA256_DEBUG));
        check("调试证书不匹配正式指纹", !UpdateManager.matchesPinnedSigner(
                new Signature[]{DEBUG_SIG}, UpdateManager.SIGNER_SHA256_STABLE));
        check("空签名集合拒绝", !UpdateManager.matchesPinnedSigner(
                new Signature[0], UpdateManager.SIGNER_SHA256_STABLE));
        check("多签名集合拒绝", !UpdateManager.matchesPinnedSigner(
                new Signature[]{RELEASE_SIG, DEBUG_SIG}, UpdateManager.SIGNER_SHA256_STABLE));
        check("空指纹拒绝", !UpdateManager.matchesPinnedSigner(
                new Signature[]{RELEASE_SIG}, ""));
    }

    private static void testCrossChannelTrust() throws Exception {
        section("跨渠道安装信任判断");
        File apk = File.createTempFile("tavernium-channel-", ".apk");
        apk.deleteOnExit();

        FakeContext stable = new FakeContext(UpdateManager.PACKAGE_STABLE);
        stable.ownSignatures = new Signature[]{new Signature(new byte[]{1, 2, 3})};
        stable.archivePkg = UpdateManager.PACKAGE_STABLE;
        stable.archiveSignatures = stable.ownSignatures;
        check("同包名同签名可更新", UpdateManager.verifyApkForInstall(stable, apk));

        stable.archivePkg = UpdateManager.PACKAGE_DEBUG;
        stable.archiveSignatures = new Signature[]{DEBUG_SIG};
        check("正式版可安装调试版", UpdateManager.verifyApkForInstall(stable, apk));
        check("跨渠道可通过安装入口", UpdateManager.install(stable, apk));

        stable.archiveSignatures = new Signature[]{RELEASE_SIG};
        check("调试包使用正式证书被拒绝", !UpdateManager.verifyApkForInstall(stable, apk));

        stable.archivePkg = "com.example.fake";
        stable.archiveSignatures = new Signature[]{RELEASE_SIG};
        check("未知包名使用正式证书被拒绝", !UpdateManager.verifyApkForInstall(stable, apk));

        stable.archivePkg = UpdateManager.PACKAGE_STABLE;
        stable.archiveSignatures = new Signature[]{DEBUG_SIG};
        check("同包名但证书不同被拒绝", !UpdateManager.verifyApkForInstall(stable, apk));

        FakeContext debug = new FakeContext(UpdateManager.PACKAGE_DEBUG);
        debug.ownSignatures = new Signature[]{DEBUG_SIG};
        debug.archivePkg = UpdateManager.PACKAGE_STABLE;
        debug.archiveSignatures = new Signature[]{RELEASE_SIG};
        check("调试版可安装正式版", UpdateManager.verifyApkForInstall(debug, apk));

        debug.archiveSignatures = new Signature[]{DEBUG_SIG};
        check("正式包使用调试证书被拒绝", !UpdateManager.verifyApkForInstall(debug, apk));

        debug.archivePkg = UpdateManager.PACKAGE_DEBUG;
        debug.archiveSignatures = new Signature[]{DEBUG_SIG};
        check("调试版同包名同签名可更新", UpdateManager.verifyApkForInstall(debug, apk));
    }

    private static Signature signature(String base64) {
        return new Signature(Base64.getDecoder().decode(base64));
    }

    private static final class FakeContext extends Context {
        final File cache;
        final String currentPackage;
        Signature[] ownSignatures = new Signature[]{new Signature(new byte[]{9})};
        String archivePkg;
        int archiveVersion = 52;
        Signature[] archiveSignatures;

        FakeContext(String currentPackage) {
            this.currentPackage = currentPackage;
            try {
                cache = Files.createTempDirectory("tk-channel-").toFile();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            archivePkg = currentPackage;
            archiveSignatures = ownSignatures;
        }

        @Override
        public File getCacheDir() {
            return cache;
        }

        @Override
        public String getPackageName() {
            return currentPackage;
        }

        @Override
        public PackageManager getPackageManager() {
            return new PackageManager() {
                @Override
                public PackageInfo getPackageInfo(String name, int flags) {
                    PackageInfo info = new PackageInfo();
                    info.packageName = currentPackage;
                    info.versionCode = 52;
                    info.signatures = ownSignatures;
                    return info;
                }

                @Override
                public PackageInfo getPackageArchiveInfo(String path, int flags) {
                    PackageInfo info = new PackageInfo();
                    info.packageName = archivePkg;
                    info.versionCode = archiveVersion;
                    info.signatures = archiveSignatures;
                    return info;
                }
            };
        }
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("--- " + name + " ---");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        check(name + "（期望 " + expected + "，实际 " + actual + "）",
                expected == null ? actual == null : expected.equals(actual));
    }
}
