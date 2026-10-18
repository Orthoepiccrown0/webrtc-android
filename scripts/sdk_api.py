#!/usr/bin/env python3
"""Check the JVM declaration ABI of the published API; protocol behavior has separate tests."""
from pathlib import Path
from zipfile import ZipFile
import difflib, os, re, subprocess, sys, tempfile
root = Path(__file__).resolve().parents[1]
update = "--update" in sys.argv
javap = Path(os.environ["JAVA_HOME"]) / "bin/javap"
modules = ["sdk-api", "sdk", "sdk-ui", "sdk-firebase", "sdk-starter"]
exports = {
    "sdk": ("DirectStreamClient", "DirectStreamConfiguration", "CallResult", "CallEvent", "SetupStatus", "InitializationStatus"),
    "sdk-ui": ("DirectStreamHostKt", "DirectStreamCallUi", "DirectStreamUiKt"),
    "sdk-firebase": ("FirebaseWorkerProvider", "FirebaseWorkerConfiguration"),
}
failed = False
for module in modules:
    aar = root / module / "build/outputs/aar" / f"{module}-release.aar"
    with ZipFile(aar) as archive, tempfile.TemporaryDirectory() as directory:
        jar = Path(directory) / "classes.jar"
        if "classes.jar" not in archive.namelist():
            signatures = ""
        else:
            jar.write_bytes(archive.read("classes.jar"))
            with ZipFile(jar) as classes:
                names = []
                for entry in sorted(classes.namelist()):
                    if not entry.endswith(".class") or "/internal/" in entry or "/testing/" in entry: continue
                    name = entry[:-6].replace("/", ".")
                    simple = name.rsplit(".", 1)[-1]
                    outer = simple.split("$", 1)[0]
                    if "$$serializer" in simple or "$WhenMappings" in simple: continue
                    if module == "sdk-api" and not name.startswith("com.epiccrown.directstream.api."): continue
                    if module != "sdk-api" and outer not in exports.get(module, ()): continue
                    if "$" in simple and (simple.split("$",1)[1][:1].islower() or simple.split("$")[-1].isdigit()): continue
                    names.append(name)
            signatures = subprocess.check_output([str(javap), "-public", "-classpath", str(jar), *names], text=True) if names else ""
            signatures = "\n".join(line for line in signatures.splitlines() if not line.startswith("Compiled from") and not re.search(r"\baccess\$|\$sdk(?:_release)?\(", line)) + "\n"
    if any(forbidden in signatures for forbidden in ["org.webrtc.", "com.epiccrown.webrtctest.", "com.epiccrown.directstream.showcase.", "com.epiccrown.directstream.internal."]):
        raise SystemExit(f"{module}: internal implementation leaked into the public ABI")
    baseline = root / "api" / f"{module}.api"
    if update:
        baseline.write_text(signatures)
    elif not baseline.exists() or baseline.read_text() != signatures:
        failed = True
        print("".join(difflib.unified_diff(baseline.read_text().splitlines(True) if baseline.exists() else [], signatures.splitlines(True), fromfile=str(baseline), tofile="current ABI")))
if failed: raise SystemExit("SDK ABI changed. Review compatibility and explicitly run sdkApiDump to update the baseline.")
print("SDK public JVM declaration ABI " + ("recorded." if update else "matches the reviewed baseline."))
