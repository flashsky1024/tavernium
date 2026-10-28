#!/usr/bin/env python3
"""Windows/Linux 共用的离线验证入口；每次的日志、桩和编译产物独立保存。"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", nargs="?", type=Path)
    parser.add_argument("--require-proc", action="store_true", help="缺少 Linux /proc 时返回失败")
    parser.add_argument("--android-jar", type=Path, help="额外用真实 Android SDK 的 android.jar 编译应用")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    app = (args.app or here.parent / "tavernium").resolve()
    results = here / "results"
    results.mkdir(exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=results))
    tmp = out / "tmp"
    tmp.mkdir()
    env = dict(os.environ, PYTHONIOENCODING="utf-8", TEMP=str(tmp), TMP=str(tmp))
    summaries = []

    def run(name, command, timeout=180):
        print("==> " + name, flush=True)
        try:
            process = subprocess.run([str(x) for x in command], cwd=here, env=env,
                                     capture_output=True, timeout=timeout)
            raw = process.stdout + process.stderr
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError:
                text = raw.decode("gb18030", errors="replace")
            code = process.returncode
        except subprocess.TimeoutExpired:
            text, code = "TIMEOUT: %ss" % timeout, 124
        (out / (name + ".log")).write_text(text, encoding="utf-8")
        totals = re.findall(r"PASSED:\s*(\d+)\s+FAILED:\s*(\d+)", text)
        skipped = re.findall(r"SKIPPED:[^\r\n]*", text)
        record = {"name": name, "exit": code, "totals": totals, "skipped": skipped}
        summaries.append(record)
        for passed, failed in totals:
            print("    PASSED: %s FAILED: %s" % (passed, failed), flush=True)
        for item in skipped:
            print("    " + item, flush=True)
        if code:
            failures = [line for line in text.splitlines() if "[FAIL]" in line]
            print("\n".join(failures) if failures else text[-3000:], flush=True)
        return code == 0

    if not all(shutil.which(tool) for tool in ("javac", "java", "node")):
        parser.error("需要 JDK 17+（javac/java）和 Node.js")
    shutil.copyfile(here / "gen_stubs.py", out / "gen_stubs.py")
    if not run("generate_stubs", [sys.executable, out / "gen_stubs.py", app], 30):
        return 1
    classes = out / "classes"
    classes.mkdir()
    files = list((out / "stubs").rglob("*.java")) + list((here / "test").rglob("*.java"))
    files += list((app / "app/src/main/java").rglob("*.java"))
    source_list = out / "sources.txt"
    source_list.write_text("\n".join('"' + f.as_posix() + '"' for f in files), encoding="utf-8")
    if not run("compile", ["javac", "-J-Dfile.encoding=UTF-8", "-J-Dstdout.encoding=UTF-8",
                           "-J-Dstderr.encoding=UTF-8", "--release", "17", "-nowarn",
                           "-encoding", "UTF-8", "-d", classes, "@" + str(source_list)], 60):
        return 1
    if args.android_jar:
        sdk_classes = out / "sdk-classes"
        sdk_classes.mkdir()
        sdk_sources = out / "sdk-sources.txt"
        sdk_files = list((app / "app/src/main/java").rglob("*.java"))
        sdk_files.append(out / "stubs/io/github/flashsky1024/tavernium/BuildConfig.java")
        sdk_sources.write_text("\n".join('"' + f.as_posix() + '"' for f in sdk_files), encoding="utf-8")
        run("compile_android_sdk", ["javac", "-J-Dfile.encoding=UTF-8", "-J-Dstdout.encoding=UTF-8",
                                    "-J-Dstderr.encoding=UTF-8", "--release", "17", "-encoding", "UTF-8",
                                    "-cp", args.android_jar.resolve(), "-d", sdk_classes, "@" + str(sdk_sources)], 60)
    java_tests = ["CacheManagerTest", "PerfMonitorTest", "UpdateManagerTest",
                  "UpdateChannelTest", "MainActivitySafetyTest", "BridgeSessionTest",
                  "ReplyNotifierTest", "UpdateSecurityTest", "MainBridgeTest",
                  "IdleControllerTest", "UrlKitCompatTest"]
    for name in java_tests:
        run(name, ["java", "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
                   "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                   "-Djava.io.tmpdir=" + str(tmp), "-Dtk.appSrc=" + str(app), "-cp", classes,
                   "io.github.flashsky1024.tavernium." + name])
    for name, constant in [("raf_throttle_test", "FRAME_RATE_HOOK_JS"),
                           ("probe_burst_test", "PERF_PROBE_SEED"),
                           ("resource_error_test", "JS_ERROR_HOOK")]:
        script = out / (constant + ".js")
        if run("extract_" + constant, [sys.executable, here / "extract_hook_js.py", app, script, constant], 30):
            run(name, ["node", here / "test" / (name + ".js"), script], 120)
    run("reply_notifications_test", ["node", here / "test/reply_notifications_test.js",
                                     app / "app/src/main/assets/reply-notifications.js"], 30)
    run("injected_bridge_test", ["node", here / "test/injected_bridge_test.js",
                                 tmp / "injected_bridge.js"], 30)
    (out / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
    passed = sum(int(p) for record in summaries for p, _ in record["totals"])
    failed = sum(int(f) for record in summaries for _, f in record["totals"])
    failed_runs = sum(bool(record["exit"]) for record in summaries)
    print("TOTAL PASSED: %d FAILED: %d FAILED RUNS: %d" % (passed, failed, failed_runs), flush=True)
    print("Logs: " + str(out), flush=True)
    return int(any(record["exit"] for record in summaries)
               or (args.require_proc and not Path("/proc/self/stat").is_file()))


if __name__ == "__main__":
    sys.exit(main())
