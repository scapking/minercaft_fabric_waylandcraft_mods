#!/usr/bin/env python3
"""Verify a single-arch waylandcraft jar contains exactly the expected
arch's native lib and (for linux) satellite (used by CI).

Usage: verify_jar.py <platform> <arch>
  platform: linux-gnu | android
  arch:     x86_64 | arm64
"""
import glob
import sys
import zipfile

platform = sys.argv[1]
arch = sys.argv[2]

jar_platform = 'linux' if platform == 'linux-gnu' else platform
jars = glob.glob(f"build/libs/waylandcraft-{jar_platform}-{arch}.jar")
assert jars, f"jar not found for {jar_platform}-{arch}"
jar = jars[0]

z = zipfile.ZipFile(jar)
names = z.namelist()
libs = [n for n in names if n.startswith(f"libwaylandcraft-{platform}-")]
sats = [n for n in names if n.startswith("xwayland-satellite-")]
deps = [n for n in names if n.startswith(f"native-deps/{platform}-{arch}/")]

print("jar:", jar)
print("native libs:", libs)
print("satellites:", sats)
print("bundled deps:", deps)

expected_lib = f"libwaylandcraft-{platform}-{arch}.so"
assert libs == [expected_lib], f"unexpected native libs: {libs}"

if platform == 'linux-gnu':
    expected_sat = f"xwayland-satellite-linux-gnu-{arch}"
    assert sats == [expected_sat], f"unexpected satellites: {sats}"
else:
    assert sats == [], f"android jar should not bundle xwayland-satellite: {sats}"

# Android jars must bundle the bionic deps + manifest; linux jars too
# (collected by collect_deps.py in CI).
assert deps, f"no bundled native deps under native-deps/{platform}-{arch}/"
assert f"native-deps/{platform}-{arch}/deps.list" in names, "deps.list manifest missing"
print("OK")
