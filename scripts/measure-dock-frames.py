"""Measure APM Dock transitions on a foreground, unlocked test emulator.

Run from the album with local search closed. Round 1 includes first visits if
the app has just started. Compare subsequent rounds separately.
"""

import argparse
import csv
import io
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


PACKAGE = "com.jinof.apm"
PAGES = ("相册", "热力图", "Agent", "设置")
# Each ordered pair occurs once in the tap circuit. Swipes are adjacent.
TAPS = (1, 0, 2, 0, 3, 1, 2, 1, 3, 2, 3, 0)
SWIPES = (1, 2, 3, 2, 1, 0)
TIMESTAMPS = (
    "IntendedVsync", "Vsync", "PerformTraversalsStart", "DrawStart",
    "SyncQueued", "FrameDeadline", "FrameCompleted",
)


def read_ui(adb):
    output = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    xml = output[output.index("<hierarchy"):output.index("</hierarchy>") + len("</hierarchy>")]
    return ET.fromstring(xml)


def bounds(node):
    return tuple(map(int, re.findall(r"-?\d+", node.attrib["bounds"])))


def dock_layout(root):
    dock = next(node for node in root.iter("node")
                if node.get("content-desc", "").startswith("固定底部 Dock"))
    centers = []
    for page in PAGES:
        label = next(node for node in dock.iter("node") if node.get("text") == page)
        left, top, right, bottom = bounds(label)
        centers.append(((left + right) // 2, (top + bottom) // 2))
    return centers


def page_bounds(root, page):
    description = f"平滑横向分页，当前{PAGES[page]}页面"
    pager = next((node for node in root.iter("node") if node.get("content-desc") == description), None)
    if pager is None:
        raise RuntimeError(f"Expected foreground Dock page {PAGES[page]}")
    return bounds(pager)


def frames(adb):
    report = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    for window in report.split("\nWindow: ")[1:]:
        if window.splitlines()[0].strip() != f"{PACKAGE}/{PACKAGE}.MainActivity":
            continue
        block = window.split("---PROFILEDATA---")[1].strip()
        result = {}
        for row in csv.DictReader(io.StringIO(block)):
            values = {name: int(row[name]) for name in TIMESTAMPS}
            start, end = values["IntendedVsync"], values["FrameCompleted"]
            if row["Flags"] == "0" and 0 < start < end < 2**63 - 1:
                result[start] = values
        return result
    raise RuntimeError("MainActivity frame statistics unavailable")


def measure(adb, writer, source, target, gesture, iteration, centers, area):
    time.sleep(0.2)
    previous = frames(adb)
    if gesture == "swipe":
        left, top, right, bottom = area
        near = left + (right - left) // 5
        far = right - (right - left) // 5
        y = (top + bottom) // 2
        start, end = (far, near) if target > source else (near, far)
        adb("shell", "input", "swipe", str(start), str(y), str(end), str(y), "240")
    else:
        x, y = centers[target]
        adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.2)
    current = frames(adb)
    # Verify the destination after the sample so UIAutomator does not
    # contribute frames to the measured transition.
    page_bounds(read_ui(adb), target)
    fresh = sorted(current.keys() - previous.keys())
    if not fresh:
        raise RuntimeError(f"No frames for {PAGES[source]} → {PAGES[target]}")
    for key in fresh:
        writer.writerow((iteration, gesture, PAGES[source], PAGES[target],
                         *(current[key][name] for name in TIMESTAMPS)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--rounds", default=4, type=int)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--first-visits", action="store_true",
                        help="Restart the emulator app on the album before each direct tap.")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or args.rounds < 1:
        parser.error("Specify a test emulator and at least one round.")

    def adb(*command):
        return subprocess.check_output([args.adb, "-s", args.serial, *command],
                                       text=True, timeout=30)

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("The selected device must be an emulator.")
    root = read_ui(adb)
    area = page_bounds(root, 0)
    centers = dock_layout(root)
    source = 0
    routes = (("first-tap", (1, 2, 3)),) if args.first_visits else (("tap", TAPS), ("swipe", SWIPES))
    with args.output.open("x", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(("round", "gesture", "source", "destination", *TIMESTAMPS))
        for iteration in range(1, args.rounds + 1):
            for gesture, route in routes:
                for target in route:
                    if args.first_visits:
                        adb("shell", "am", "force-stop", PACKAGE)
                        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
                        time.sleep(1.2)
                        page_bounds(read_ui(adb), 0)
                        source = 0
                    measure(adb, writer, source, target, gesture, iteration, centers, area)
                    output.flush()
                    source = target
                print(f"Round {iteration} {gesture} complete", flush=True)


if __name__ == "__main__":
    main()
