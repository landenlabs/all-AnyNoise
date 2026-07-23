#!/usr/bin/env python3
"""
Lists registered devices from the `devices` Firestore collection.

Auth: reuses your existing `gcloud auth login` session (run
`gcloud auth login` once if you haven't). No service-account key needed.

Usage:
  python3 scripts/list_devices.py                 # table to stdout
  python3 scripts/list_devices.py --csv            # CSV to stdout
  python3 scripts/list_devices.py --csv > devices.csv   # then File > Import in Google Sheets
  python3 scripts/list_devices.py --project all-anynoise
"""
import argparse
import csv
import json
import os
import shutil
import subprocess
import sys

DEFAULT_PROJECT = "all-anynoise"
COLLECTION = "devices"

# Fallback locations in case `gcloud` isn't on PATH for this process (common
# when the SDK's PATH setup lives in .zshrc/.bash_profile but this script is
# run from a shell/tool that didn't source it).
GCLOUD_FALLBACK_PATHS = [
    os.path.expanduser("~/google-cloud-sdk/bin/gcloud"),
    "/usr/local/google-cloud-sdk/bin/gcloud",
    "/opt/homebrew/bin/gcloud",
    "/usr/lib/google-cloud-sdk/bin/gcloud",
]


def find_gcloud():
    found = shutil.which("gcloud")
    if found:
        return found
    for path in GCLOUD_FALLBACK_PATHS:
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path
    sys.exit(
        "Could not find the `gcloud` executable (checked PATH and "
        f"{GCLOUD_FALLBACK_PATHS}).\n"
        "Install the Google Cloud SDK, or set GCLOUD_BIN to its full path."
    )


def get_access_token():
    gcloud = os.environ.get("GCLOUD_BIN") or find_gcloud()
    result = subprocess.run(
        [gcloud, "auth", "print-access-token"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        sys.exit(f"Failed to get gcloud access token. Run `gcloud auth login` first.\n{result.stderr}")
    return result.stdout.strip()


def fetch_documents(project, token):
    # Shells out to curl (rather than urllib) so this doesn't depend on
    # Python's bundled CA bundle being set up correctly on your machine.
    docs = []
    page_token = None
    base_url = (
        f"https://firestore.googleapis.com/v1/projects/{project}"
        f"/databases/(default)/documents/{COLLECTION}"
    )
    while True:
        url = base_url + (f"?pageToken={page_token}" if page_token else "")
        result = subprocess.run(
            ["curl", "-s", "-H", f"Authorization: Bearer {token}", url],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            sys.exit(f"curl failed: {result.stderr}")
        data = json.loads(result.stdout)
        if "error" in data:
            sys.exit(f"Firestore API error: {data['error'].get('message', data['error'])}")
        docs.extend(data.get("documents", []))
        page_token = data.get("nextPageToken")
        if not page_token:
            break
    return docs


def decode_value(value):
    if "stringValue" in value:
        return value["stringValue"]
    if "timestampValue" in value:
        return value["timestampValue"]
    if "integerValue" in value:
        return value["integerValue"]
    if "booleanValue" in value:
        return value["booleanValue"]
    if "arrayValue" in value:
        items = value["arrayValue"].get("values", [])
        return [decode_value(v) for v in items]
    return None


def decode_doc(doc):
    device_id = doc["name"].rsplit("/", 1)[-1]
    fields = {k: decode_value(v) for k, v in doc.get("fields", {}).items()}
    return {
        "deviceId": device_id,
        "displayName": fields.get("displayName", ""),
        "fcmToken": fields.get("fcmToken", ""),
        "mutedListenerIds": fields.get("mutedListenerIds", []) or [],
        "updatedAt": fields.get("updatedAt", ""),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default=DEFAULT_PROJECT)
    parser.add_argument("--csv", action="store_true", help="output CSV instead of a table")
    args = parser.parse_args()

    token = get_access_token()
    rows = [decode_doc(d) for d in fetch_documents(args.project, token)]
    rows.sort(key=lambda r: r["updatedAt"], reverse=True)

    if args.csv:
        writer = csv.writer(sys.stdout)
        writer.writerow(["deviceId", "displayName", "hasFcmToken", "mutedListenerCount", "updatedAt"])
        for r in rows:
            writer.writerow([
                r["deviceId"], r["displayName"], bool(r["fcmToken"]),
                len(r["mutedListenerIds"]), r["updatedAt"],
            ])
        return

    if not rows:
        print("No registered devices found.")
        return

    print(f"{len(rows)} registered device(s):\n")
    for r in rows:
        token_status = "OK" if r["fcmToken"] else "MISSING"
        print(f"- {r['displayName']}  ({r['deviceId']})")
        print(f"    fcmToken: {token_status}   muted listeners: {len(r['mutedListenerIds'])}   updated: {r['updatedAt']}")


if __name__ == "__main__":
    main()
