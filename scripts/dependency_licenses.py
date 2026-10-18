#!/usr/bin/env python3
"""Generate a review inventory from resolved coordinates and cached Maven license declarations."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
rows = []
for coordinate in (root / "build/reports/sdk-dependencies.txt").read_text().splitlines():
    group, name, version = coordinate.split(":")
    declarations = set()
    for pom in (cache / group / name / version).glob("*/*.pom"):
        xml = ET.parse(pom).getroot()
        for license_node in xml.findall(".//{*}licenses/{*}license"):
            label = license_node.findtext("{*}name", "Unspecified")
            url = license_node.findtext("{*}url", "")
            declarations.add(f"[{label}]({url})" if url.startswith("https://") else label)
    rows.append(f"| `{coordinate}` | {'; '.join(sorted(declarations)) or 'Review upstream license (not present in cached POM)'} |")
output = root / "docs/DEPENDENCIES.md"
output.write_text("""# Resolved SDK dependency/license inventory

Generated from the five modules' release runtime graphs. Maven POM declarations are evidence for review, not legal clearance. Review upstream artifacts, bundled native WebRTC notices and transitive distribution obligations before public publishing. Original DirectStream source is licensed under Apache 2.0; third-party components retain their own licenses. Missing declarations require manual review. Android build tooling/test dependencies are outside this shipped-runtime inventory.

This is the union of five independently resolved graphs: multiple versions of a dependency can appear. The consuming application resolves its own final graph. Use [publication checks](PUBLICATION.md) for release gates.

Regenerate with `bash gradlew sdkDependencyInventory` then `python3 scripts/dependency_licenses.py`. Format the generated table with `worker/node_modules/.bin/prettier --write docs/DEPENDENCIES.md`.

| Resolved dependency | Cached Maven license declaration |
| --- | --- |
""" + "\n".join(rows) + "\n")
print(f"Wrote {len(rows)} dependency entries to {output}")
