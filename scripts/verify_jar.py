#!/usr/bin/env python3
"""Verify a single-arch waylandcraft jar contains exactly the expected
arch's native lib and satellite (used by CI)."""
import glob
import sys
import zipfile

arch = sys.argv[1]
jars = glob.glob(f"build/libs/waylandcraft-linux-{arch}.jar")
assert jars, f"jar not found for {arch}"
jar = jars[0]

z = zipfile.ZipFile(jar)
names = z.namelist()
libs = [n for n in names if n.startswith("libwaylandcraft-linux-gnu-")]
sats = [n for n in names if n.startswith("xwayland-satellite-linux-gnu-")]

print("jar:", jar)
print("native libs:", libs)
print("satellites:", sats)

expected_lib = f"libwaylandcraft-linux-gnu-{arch}.so"
expected_sat = f"xwayland-satellite-linux-gnu-{arch}"
assert libs == [expected_lib], f"unexpected native libs: {libs}"
assert sats == [expected_sat], f"unexpected satellites: {sats}"
print("OK")
