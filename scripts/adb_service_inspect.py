"""Explicit adb-triggered single inspection through our Debug AccessibilityService."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--confirmed-page", action="store_true", required=True)
    parser.add_argument("--include-unimportant-views", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        raise RuntimeError("Invalid package")

    def adb(*command):
        result = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, timeout=20)
        if result.returncode:
            raise RuntimeError("adb failed")
        return result.stdout.decode("utf-8", errors="replace")

    def foreground():
        state = adb("shell", "dumpsys", "activity", "activities")
        matches = re.findall(r"(?:topResumedActivity|mResumedActivity)[^\r\n]*?\bu(\d+)\s+([\w.]+/[\w.$]+)", state)
        if len(set(matches)) != 1 or not matches[0][1].startswith(args.package + "/"):
            raise RuntimeError("Selected app not foreground")
        return matches[0]

    before = foreground()
    version = re.search(r"versionName=([A-Za-z0-9._+-]{1,80})\s", adb("shell", "dumpsys", "package", args.package))
    if not version:
        raise RuntimeError("App version unavailable")
    command = ["shell", "am", "broadcast", "-n", "dev.linguabridge.debug/dev.linguabridge.accessibility.DebugInspectReceiver",
               "-a", "dev.linguabridge.debug.INSPECT_CONFIRMED_PAGE_ONCE", "--es", "appVersion", version[1],
               "--es", "targetPackage", args.package]
    if args.include_unimportant_views:
        command += ["--ez", "includeUnimportantViews", "true"]
    adb(*command)
    status = "RUNNING"
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        status = adb("exec-out", "run-as", "dev.linguabridge.debug", "cat", "files/fixtures/adb-status.txt").strip()
        if status != "RUNNING":
            break
        time.sleep(0.25)
    if status != "COMPLETE":
        safe_status = status if re.fullmatch(r"[A-Z_]+", status) else "UNKNOWN"
        print(json.dumps({"status": safe_status}))
        raise SystemExit(1)
    if foreground() != before:
        raise RuntimeError("Foreground changed")
    raw = adb("exec-out", "run-as", "dev.linguabridge.debug", "cat", "files/fixtures/m0-fixture.json")
    fixture = json.loads(raw)
    if fixture.get("schemaVersion") != 6 or fixture.get("inspectorVersion") != "0.3-page-assistant" or fixture.get("targetPackage") != args.package:
        raise RuntimeError("Unexpected fixture version")
    if any(node.get(field) not in (None, "[REDACTED]") for node in fixture["nodes"] for field in ("text", "description")):
        raise RuntimeError("Fixture contains non-default text; withheld")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": status, "nodeCount": len(fixture["nodes"]),
                      "diagnostics": fixture.get("diagnostics"), "savedPath": str(args.output.resolve())}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Service diagnostic did not complete safely; raw output withheld.")
        raise SystemExit(1)
