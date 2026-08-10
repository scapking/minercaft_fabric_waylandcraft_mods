#!/usr/bin/env python3
"""Collect the non-glibc .so runtime dependencies of a native library into
native-libs/deps/, so the jar can bundle them for stripped runtimes such as
Android launchers. Usage: collect_deps.py <path-to-libwaylandcraft.so>"""
import os
import re
import shutil
import subprocess
import sys

SO = sys.argv[1]
OUT = "native-libs/deps"

# Provided by the target runtime (glibc JRE on Android launchers too)
EXCLUDE = {
    "libc.so.6", "libm.so.6", "libgcc_s.so.1", "libdl.so.2",
    "libpthread.so.0", "librt.so.1", "libstdc++.so.6",
    "libresolv.so.2", "libutil.so.1", "libnsl.so.1",
    "libcrypt.so.1", "libnss_files.so.2", "libnss_dns.so.2",
}
EXCLUDE_PREFIX = ("ld-linux-", "ld64.so", "libc.musl-", "libSystem.B")


def needed(so):
    out = subprocess.run(["readelf", "-d", so], capture_output=True, text=True)
    return re.findall(r"Shared library: \[([^\]]+)\]", out.stdout)


def resolve(name):
    out = subprocess.run(["ldd", SO], capture_output=True, text=True).stdout
    for line in out.splitlines():
        m = re.match(rf"\s*{re.escape(name)}\s*=>\s*(\S+)", line)
        if m:
            return m.group(1)
    return None


os.makedirs(OUT, exist_ok=True)
queue = needed(SO)
seen = set()
while queue:
    name = queue.pop()
    if name in seen or name in EXCLUDE or name.startswith(EXCLUDE_PREFIX):
        continue
    seen.add(name)
    path = resolve(name)
    if not path or not os.path.exists(path):
        print(f"WARN: cannot resolve {name}, skipping")
        continue
    dest = os.path.join(OUT, name)
    shutil.copy2(path, dest)
    print(f"bundling {name} <- {path}")
    queue.extend(needed(path))
