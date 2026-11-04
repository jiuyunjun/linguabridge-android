"""One explicitly user-authorized adb diagnostic. Never persist or print raw UI XML."""
import argparse
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

LINE = "jp.naver.line.android"
SELF = "dev.linguabridge.debug"


class DiagnosticError(RuntimeError):
    """Fixed non-sensitive messages only; never wrap raw adb or parser output."""


def sanitize(xml: str, expected_package: str = LINE) -> dict:
    if expected_package not in (LINE, SELF):
        raise ValueError("Only the confirmed LINE test page or our own debug app is supported")
    root = ET.fromstring(xml)
    nodes, tokens = [], {}
    stats = {"visited": 0, "blockedSubtrees": 0, "invisibleSkipped": 0}
    truncated = False

    def token(value):
        if not value:
            return None
        if value not in tokens:
            tokens[value] = f"TEXT_{len(tokens) + 1:03d}"
        return tokens[value]

    def visit(node, path, parent, depth):
        nonlocal truncated
        if stats["visited"] >= 400 or depth > 24:
            truncated = True
            return
        stats["visited"] += 1
        attrs = node.attrib
        class_name = attrs.get("class", "")
        # Prune before consulting any text/content-desc field.
        if (attrs.get("package") != expected_package or attrs.get("password") == "true"
                or attrs.get("editable") == "true" or "edittext" in class_name.lower()
                or attrs.get("accessibility-data-sensitive") == "true"):
            stats["blockedSubtrees"] += 1
            return
        if attrs.get("visible-to-user", "true") == "false":
            stats["invisibleSkipped"] += 1
        else:
            resource_id = attrs.get("resource-id", "")
            bounds = attrs.get("bounds", "")
            nodes.append({
                "path": path, "parentPath": parent,
                "className": class_name if re.fullmatch(r"[\w.$]+", class_name, re.ASCII) else "[REDACTED_CLASS]",
                "resourceId": resource_id if re.fullmatch(r"[\w.]+:id/[\w.$]+", resource_id, re.ASCII) else None,
                "bounds": bounds if re.fullmatch(r"\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]", bounds) else None,
                "text": token(attrs.get("text")), "description": token(attrs.get("content-desc")),
                "sender": "UNKNOWN",
            })
        for index, child in enumerate(node):
            if stats["visited"] >= 400:
                truncated = True
                break
            visit(child, f"{path}/{index}", path, depth + 1)

    for index, child in enumerate(root):
        if stats["visited"] >= 400:
            truncated = True
            break
        visit(child, str(index), None, 0)
    return {"schemaVersion": 1, "source": "adb-uiautomator-not-app-accessibility-service",
            "expectedPackage": expected_package,
            "kind": "automatically-redacted-diagnostic-not-messages", "compatibility": "UNVERIFIED",
            "truncated": truncated, "diagnostics": stats, "nodes": nodes}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--target", choices=("line",))
    target.add_argument("--self-test", action="store_true", help="Only inspect our own debug app as a control")
    args = parser.parse_args()
    expected_package = SELF if args.self_test else LINE

    def adb(*command, timeout=25):
        result = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, timeout=timeout)
        if result.returncode:
            raise DiagnosticError("adb command failed; raw output withheld")
        return result.stdout.decode("utf-8", errors="replace")

    def foreground():
        state = adb("shell", "dumpsys", "activity", "activities")
        matches = re.findall(r"(?:topResumedActivity|mResumedActivity)[^\r\n]*?\bu(\d+)\s+([\w.]+/[\w.$]+)", state)
        if len(set(matches)) != 1 or not matches[0][1].startswith(expected_package + "/"):
            raise DiagnosticError("Confirmed target is not the sole foreground activity; no UI tree requested")
        return matches[0]

    before = foreground()
    # Write directly to the child process stdout descriptor, not a phone or host raw file.
    raw = adb("exec-out", "uiautomator", "dump", "/proc/self/fd/1")
    if len(raw) > 4 * 1024 * 1024:
        raise DiagnosticError("Diagnostic XML exceeds size limit; raw output withheld")
    start, end = raw.find("<hierarchy"), raw.rfind("</hierarchy>")
    if start < 0 or end < start:
        raise DiagnosticError("No XML hierarchy returned; raw output withheld")
    safe = sanitize(raw[start:end + len("</hierarchy>")], expected_package)
    del raw
    if foreground() != before:
        raise DiagnosticError("Foreground activity changed; diagnostic discarded")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(safe, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"savedPath": str(args.output.resolve()), "nodeCount": len(safe["nodes"]),
                      "truncated": safe["truncated"], "diagnostics": safe["diagnostics"]}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except DiagnosticError as error:
        print(str(error))
        raise SystemExit(1)
    except Exception:
        # Never echo XML, exception values from parsers, adb stderr or chat text.
        print("Diagnostic could not complete safely; raw output withheld.")
        raise SystemExit(1)
