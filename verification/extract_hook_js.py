#!/usr/bin/env python3
"""从 MainActivity.java 提取 FRAME_RATE_HOOK_JS 常量，输出为可直接执行的 .js。

帧率节流脚本以单引号/无引号书写，Java 字符串里不含转义序列，
因此提取结果为原样代码，可交给 node 做行为测试。

用法: ./extract_hook_js.py <应用目录> <输出文件> [常量名]
默认提取 FRAME_RATE_HOOK_JS；传 PERF_PROBE_SEED 可提取性能探针。
"""
import os
import re
import sys

CONST = sys.argv[3] if len(sys.argv) > 3 else "FRAME_RATE_HOOK_JS"
REL = "app/src/main/java/wiki/nandflash/tavernkeep/MainActivity.java"
PATTERN = re.compile(
    r"String\s+" + CONST + r"\s*=\s*\"((?:[^\"\\]|\\.)*)\"\s*;")


def main():
    app = sys.argv[1] if len(sys.argv) > 1 else "."
    out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/frame_rate_hook.js"
    src = os.path.join(app, REL)
    if not os.path.exists(src):
        print("找不到 " + src, file=sys.stderr)
        sys.exit(2)
    with open(src, encoding="utf-8") as f:
        text = f.read()
    m = PATTERN.search(text)
    if not m:
        print("未能在源码中找到 " + CONST + " 常量", file=sys.stderr)
        sys.exit(2)
    js = m.group(1)
    if "\\" in js:
        print("脚本含转义字符，提取逻辑需要更新（不应出现）", file=sys.stderr)
        sys.exit(2)
    with open(out, "w", encoding="utf-8") as f:
        f.write(js)
    print("extracted %s: %d bytes -> %s" % (CONST, len(js), out))


if __name__ == "__main__":
    main()
