#!/usr/bin/env python3
"""Checks that the two packaged LWJGL jars on the SDL launch classpath are one release.

26.3 runs `org.lwjgl.system.*` from `lwjgl-glfw-classes.jar` (the Android-patched bridge) and
`org.lwjgl.sdl.*` from `lwjgl-sdl-3.4.1.jar`. They are put on the classpath together, so their
callback API has to match exactly. When it did not, the game died in
`RenderSystem.initBackendSystem` with:

    java.lang.InternalError: java.lang.NoSuchMethodError:
      'void org.lwjgl.system.CallbackDescriptor.<init>(java.lang.Class, java.lang.Class,
      java.lang.reflect.Method)'

(old-style, top-level `CallbackDescriptor`) while the loaded core jar only had the nested
`Callback.Descriptor`. This test is the guard for that: bump one jar without the other and the
build fails instead of the user's launch.
"""

import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "TurtleLauncher/src/main/assets/components/lwjgl3"
BRIDGE = ASSETS / "lwjgl-glfw-classes.jar"
SDL = ASSETS / "lwjgl-sdl-3.4.1.jar"

# The descriptor constructor the 3.4.1 pair uses, and the one the broken mix used.
DESC_CTOR_341 = "(Ljava/lang/invoke/MethodHandles$Lookup;Lorg/lwjgl/system/libffi/FFICIF;)V"
DESC_CTOR_TOP_LEVEL = (
    "(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/reflect/Method;)V"
)

failures = []


def fail(message):
    failures.append(message)


def ctors(data):
    """Every method descriptor ('(…)V') in a class file's constant pool."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    pos = 8
    count = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2
    index = 1
    found = set()
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            text = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
            if text.startswith("(") and text.endswith(")V"):
                found.add(text)
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 1
    return found


def main():
    for jar in (BRIDGE, SDL):
        if not jar.is_file():
            fail(f"missing packaged jar: {jar.relative_to(ROOT)}")
    if failures:
        return report()

    with zipfile.ZipFile(BRIDGE) as bridge, zipfile.ZipFile(SDL) as sdl:
        bridge_names = set(bridge.namelist())
        sdl_names = set(sdl.namelist())

        # 1. The core classes the SDL bindings extend must be in the bridge.
        for name in (
            "org/lwjgl/system/Callback.class",
            "org/lwjgl/system/Callback$Descriptor.class",
            "org/lwjgl/system/CallbackI.class",
            "org/lwjgl/system/Library.class",
        ):
            if name not in bridge_names:
                fail(f"bridge jar lost {name} - org.lwjgl.sdl cannot link against it")

        # 2. The bridge's callback descriptor must be the 3.4.1 one.
        descriptor = bridge.read("org/lwjgl/system/Callback$Descriptor.class")
        if DESC_CTOR_341 not in ctors(descriptor):
            fail(
                "bridge Callback$Descriptor does not have the 3.4.1 constructor "
                f"{DESC_CTOR_341} - the packaged jars are from different releases"
            )

        # 3. The SDL bindings must never reference the old top-level CallbackDescriptor, and
        #    must build their descriptors through the bridge's 3.4.1 constructor.
        sdl_classes = [n for n in sdl_names if n.startswith("org/lwjgl/sdl/") and n.endswith(".class")]
        if not sdl_classes:
            fail("lwjgl-sdl jar contains no org/lwjgl/sdl classes")
        old_api = []
        new_api = 0
        for name in sdl_classes:
            data = sdl.read(name)
            if b"org/lwjgl/system/CallbackDescriptor" in data:
                old_api.append(name)
            if DESC_CTOR_341 in ctors(data):
                new_api += 1
        if old_api:
            fail(
                "lwjgl-sdl uses the pre-3.4 top-level CallbackDescriptor in "
                + ", ".join(sorted(old_api)[:3])
                + " - that is the jar that caused NoSuchMethodError at RenderSystem.initBackendSystem"
            )
        if new_api == 0:
            fail(
                "lwjgl-sdl never calls the bridge's Callback$Descriptor"
                f"{DESC_CTOR_341} constructor - it was built against another LWJGL"
            )

        # 4. Launcher no longer mixes the version JSON's lwjgl jars into SDL launches.
        tools = (ROOT / "TurtleLauncher/src/main/java/net/endiq/launcher/Tools.java").read_text()
        if "public static final String SDL_JAR = \"lwjgl-sdl-3.4.1.jar\";" not in tools:
            fail("Tools.SDL_JAR does not point at lwjgl-sdl-3.4.1.jar")
        override = tools.split("public static String getLwjglAbiOverrideClasspath", 1)[-1]
        if 'return "";' not in override.split("List<String> paths", 1)[0]:
            fail(
                "getLwjglAbiOverrideClasspath no longer returns an empty classpath for SDL "
                "launches - the version JSON's lwjgl pair would shadow the packaged one again"
            )

    print(
        f"OK: bridge + {SDL.name} share the LWJGL 3.4.1 callback API "
        f"({len(sdl_classes)} SDL classes checked, {new_api} build descriptors through the bridge)"
    )
    return report()


def report():
    if failures:
        print("FAIL: packaged LWJGL jars are inconsistent:", file=sys.stderr)
        for message in failures:
            print("  - " + message, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
