"""Read-only inventory of the recovery manifest. Never runs project code."""

import csv
import json
from pathlib import Path
import sys


def inventory(root):
    with (root / "MANIFEST.tsv").open(encoding="utf-8", newline="") as source:
        entries = list(csv.DictReader(source, delimiter="\t"))
    missing = sorted({entry["path"] for entry in entries if not (root / entry["path"]).is_file()})
    groups = {
        "main_java": [path for path in missing if path.startswith("src/main/java/")],
        "main_resources": [path for path in missing if path.startswith("src/main/resources/")],
        "deployment": [path for path in missing if path.startswith("deploy/")],
        "build": [path for path in missing if path in {"gradlew", "gradlew.bat", "settings.gradle.kts"}],
        "tests": [path for path in missing if path.startswith("src/test/")],
    }
    for path in ("gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
        if not (root / path).is_file():
            groups["build"].append(path)
    return {
        "complete": not missing and not groups["build"],
        "missing_counts": {group: len(paths) for group, paths in groups.items()},
        "missing_files": groups,
        "missing_manifest_paths": missing,
    }


if __name__ == "__main__":
    result = inventory(Path(__file__).resolve().parents[1])
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["complete"] else 1)
