"""
PlatformIO extra script: erase the otadata partition before USB upload.

When USB-flashing OTA-capable firmware, PlatformIO writes to app0 (ota_0) but
does NOT reset the otadata partition. If otadata still points to app1 from a
previous OTA, the bootloader boots from the wrong partition — breaking OTA.

This script runs before upload and erases the otadata region so the bootloader
defaults to the first OTA partition.
"""

Import("env")

import csv
import os
import subprocess


def find_otadata(partition_csv):
    """Parse the partition CSV and return (offset, size) for the otadata entry."""
    with open(partition_csv, "r") as f:
        for row in csv.reader(f):
            if len(row) < 5:
                continue
            name = row[0].strip().lstrip("#").strip()
            if name == "otadata":
                offset = int(row[3].strip(), 0)
                size = int(row[4].strip(), 0)
                return offset, size
    return None, None


def erase_otadata(*args, **kwargs):
    """Erase the otadata partition so the bootloader defaults to ota_0."""
    # Resolve partition table path
    partitions = env.subst("$PARTITIONS_TABLE_CSV")
    if not partitions or not os.path.isfile(partitions):
        # Try board config
        board_cfg = env.BoardConfig()
        partitions = board_cfg.get("build.partitions", board_cfg.get("build.arduino.partitions", ""))
        if partitions and not os.path.isabs(partitions):
            # Check project dir first, then framework tools
            project_path = os.path.join(env.subst("$PROJECT_DIR"), partitions)
            if os.path.isfile(project_path):
                partitions = project_path
            else:
                framework_path = os.path.join(
                    env.PioPlatform().get_package_dir("framework-arduinoespressif32"),
                    "tools", "partitions", partitions,
                )
                if os.path.isfile(framework_path):
                    partitions = framework_path

    if not partitions or not os.path.isfile(partitions):
        print("[erase_otadata] WARNING: Could not find partition table, skipping")
        return

    offset, size = find_otadata(partitions)
    if offset is None:
        print("[erase_otadata] WARNING: No otadata entry found in partition table, skipping")
        return

    esptool = env.subst("$PYTHONEXE") + " -m esptool"
    port = env.subst("$UPLOAD_PORT")
    speed = env.subst("$UPLOAD_SPEED") or "921600"

    port_args = f"--port {port}" if port else ""
    cmd = (
        f"{esptool} {port_args} --baud {speed} "
        f"erase_region {offset:#x} {size:#x}"
    )

    print(f"[erase_otadata] Erasing otadata at {offset:#x} ({size} bytes) from {partitions}")
    result = subprocess.run(cmd, shell=True)
    if result.returncode != 0:
        print("[erase_otadata] WARNING: Failed to erase otadata (device may not be connected)")


env.AddPreAction("upload", erase_otadata)
