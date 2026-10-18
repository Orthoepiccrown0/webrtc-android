#!/usr/bin/env python3
"""Verify locally published SDK metadata and the four standalone consumer APKs."""
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.search(r"^sdkVersion=(.+)$", (ROOT / "gradle.properties").read_text(), re.M)[1]
GROUP = re.search(r"^sdkGroup=(.+)$", (ROOT / "gradle.properties").read_text(), re.M)[1]
REPO = ROOT / "build/sdk-maven" / GROUP.replace(".", "/")
EDGES = {
    "directstream": {"directstream-ui", "directstream-firebase"},
    "directstream-api": set(),
    "directstream-runtime": {"directstream-api"},
    "directstream-ui": {"directstream-runtime"},
    "directstream-firebase": {"directstream-api"},
}
SIGNED = "--signed" in sys.argv
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition, message):
    if not condition:
        raise SystemExit(message)


metadata = {}
poms = {}
for artifact, expected in EDGES.items():
    base = REPO / artifact / VERSION / f"{artifact}-{VERSION}"
    for suffix in (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module"):
        file = Path(str(base) + suffix)
        require(file.is_file(), f"Missing {file}")
        for algorithm in ("md5", "sha1"):
            checksum = Path(str(file) + "." + algorithm)
            require(checksum.is_file() and checksum.read_text().strip() == hashlib.new(algorithm, file.read_bytes()).hexdigest(),
                    f"{artifact}: invalid {suffix} {algorithm} checksum")
        if SIGNED:
            require(Path(str(file) + ".asc").is_file(), f"{artifact}: missing {suffix} PGP signature")
    pom = ET.parse(str(base) + ".pom").getroot()
    require(pom.findtext("m:groupId", namespaces=NS) == GROUP, f"{artifact}: POM namespace")
    require(pom.findtext("m:licenses/m:license/m:url", namespaces=NS) == "https://www.apache.org/licenses/LICENSE-2.0.txt", f"{artifact}: Apache 2.0 metadata")
    require(pom.findtext("m:developers/m:developer/m:id", namespaces=NS) == "orthoepiccrown0", f"{artifact}: developer metadata")
    require(pom.findtext("m:scm/m:developerConnection", namespaces=NS), f"{artifact}: SCM metadata")
    require(pom.findtext("m:version", namespaces=NS) == VERSION, f"{artifact}: POM version")
    require(pom.findtext("m:artifactId", namespaces=NS) == artifact, f"{artifact}: POM identity")
    deps = pom.findall("m:dependencies/m:dependency", NS)
    poms[artifact] = {
        (d.findtext("m:groupId", namespaces=NS), d.findtext("m:artifactId", namespaces=NS)):
        (d.findtext("m:version", namespaces=NS), d.findtext("m:scope", namespaces=NS))
        for d in deps
    }
    own = {name for (group, name), (version, scope) in poms[artifact].items() if group == GROUP}
    require(own == expected, f"{artifact}: unexpected POM SDK edges: {own}")
    for (group, name), (version, scope) in poms[artifact].items():
        if group == GROUP:
            require(version == VERSION and scope == "compile", f"{artifact}: {name} version/scope")
    module = json.loads(Path(str(base) + ".module").read_text())
    require(module["component"]["group"] == GROUP, f"{artifact}: module namespace")
    require(module["component"]["version"] == VERSION, f"{artifact}: module version")
    metadata[artifact] = {}
    for usage in ("java-api", "java-runtime"):
        variants = [v for v in module["variants"] if v["attributes"].get("org.gradle.usage") == usage
                    and v["attributes"].get("org.gradle.category") == "library"]
        require(len(variants) == 1, f"{artifact}: missing or ambiguous {usage} variant")
        dependencies = variants[0].get("dependencies", [])
        metadata[artifact][usage] = {(d["group"], d["module"]) for d in dependencies}
        require({d["module"] for d in dependencies if d["group"] == GROUP} == expected,
                f"{artifact}: {usage} SDK edges")
        for d in dependencies:
            if d["group"] == GROUP:
                require(d["version"]["requires"] == VERSION, f"{artifact}: mixed SDK versions")
    with ZipFile(str(base) + ".aar") as aar:
        require("classes.jar" in aar.namelist(), f"{artifact}: missing classes/notices jar")
        with ZipFile(BytesIO(aar.read("classes.jar"))) as classes:
            require(f"META-INF/{artifact}/LICENSE" in classes.namelist(), f"{artifact}: missing bundled license")
            require(f"META-INF/{artifact}/NOTICE" in classes.namelist(), f"{artifact}: missing bundled notices")
            if artifact == "directstream-ui":
                require(f"META-INF/{artifact}/third-party/material-icons-NOTICE.txt" in classes.namelist(), "Missing Material Icons attribution")
    with ZipFile(str(base) + "-javadoc.jar") as docs:
        require({"index.html", "README.md", "docs/README.md", "LICENSE", "NOTICE"} <= set(docs.namelist()), f"{artifact}: incomplete documentation jar")
    with ZipFile(str(base) + "-sources.jar") as sources:
        require("LICENSE" in sources.namelist(), f"{artifact}: missing sources license")
        if artifact != "directstream":
            require(any(n.endswith(".kt") for n in sources.namelist()), f"{artifact}: empty sources")
    print(f"Verified {artifact}:{VERSION} publication")

webrtc = ("io.getstream", "stream-webrtc-android")
require(poms["directstream-runtime"].get(webrtc) == ("1.3.9", "runtime"), "WebRTC POM runtime dependency")
require(webrtc in metadata["directstream-runtime"]["java-runtime"], "WebRTC runtime metadata")
require(webrtc not in metadata["directstream-runtime"]["java-api"], "WebRTC leaked to compile API")
serialization = ("org.jetbrains.kotlinx", "kotlinx-serialization-core")
require(serialization in metadata["directstream-api"]["java-api"], "Public serializers unavailable at compile time")
require(poms["directstream-api"].get(serialization, (None, None))[1] == "compile", "Serialization POM scope")

# Check the public constant in the published bytecode, not just its source template.
java_home = os.environ.get("JAVA_HOME")
javap = str(Path(java_home) / "bin/javap") if java_home else shutil.which("javap")
require(javap, "javap is required (use the repository JDK)")
with ZipFile(REPO / "directstream-runtime" / VERSION / f"directstream-runtime-{VERSION}.aar") as aar:
    with tempfile.TemporaryDirectory() as directory:
        jar = Path(directory) / "classes.jar"
        jar.write_bytes(aar.read("classes.jar"))
        declarations = subprocess.check_output(
            [javap, "-constants", "-classpath", str(jar), "com.epiccrown.directstream.DirectStreamClientKt"], text=True)
        require(f'DIRECT_STREAM_VERSION = "{VERSION}";' in declarations, "Runtime constant version mismatch")

abis = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
for flavor in ("core", "starter"):
    for variant in ("debug", "release"):
        folder = ROOT / "integration-consumer/build/outputs/apk" / flavor / variant
        apks = list(folder.glob("*.apk"))
        require(len(apks) == 1, f"Build exactly one {flavor}/{variant} APK before verification")
        with ZipFile(apks[0]) as apk:
            actual = {n.split("/")[1] for n in apk.namelist()
                      if re.fullmatch(r"lib/[^/]+/libjingle_peerconnection_so\.so", n)}
            require(actual == abis, f"{apks[0].name}: native ABIs {actual}")
            dex = b"".join(apk.read(n) for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            for cls in (b"Lorg/webrtc/PeerConnectionFactory;", b"Lorg/webrtc/JniCommon;"):
                require(cls in dex, f"{apks[0].name}: missing JNI class {cls!r}")
        print(f"Verified {flavor}/{variant}: four WebRTC ABIs and JNI classes")
    rules = ROOT / "integration-consumer/build/outputs/mapping" / f"{flavor}Release/configuration.txt"
    require(rules.is_file() and "-keep class org.webrtc.**" in rules.read_text(),
            f"{flavor}: missing effective WebRTC keep rule")
print("SDK publication, runtime version, transitive dependency and consumer packaging checks passed.")
