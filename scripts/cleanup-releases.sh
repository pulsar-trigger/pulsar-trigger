#!/usr/bin/env bash
#
# Delete every GitHub release on pulsar-trigger/pulsar-trigger except the latest
# app-v* release and the latest firmware-v* release. Also removes the underlying
# tag for each deleted release.
#
# Usage:
#   GH_TOKEN=<personal-access-token> ./scripts/cleanup-releases.sh           # dry-run
#   GH_TOKEN=<personal-access-token> ./scripts/cleanup-releases.sh --apply   # actually delete
#
# The token needs `repo` scope (or `public_repo` for a public repo).

set -euo pipefail

REPO="pulsar-trigger/pulsar-trigger"
APPLY=false
[[ "${1:-}" == "--apply" ]] && APPLY=true

if [[ -z "${GH_TOKEN:-}" ]]; then
    echo "GH_TOKEN env var is required." >&2
    exit 1
fi

api() {
    curl -sS \
        -H "Authorization: Bearer $GH_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "$@"
}

# Fetch all releases (paginated; max 100/page).
echo "→ Fetching releases…"
releases_json=$(api "https://api.github.com/repos/${REPO}/releases?per_page=100")

# Find latest app + firmware (releases come back newest first).
latest_app=$(echo "$releases_json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
for r in data:
    if r["tag_name"].startswith("app-v"):
        print(r["tag_name"])
        break
')
latest_fw=$(echo "$releases_json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
for r in data:
    if r["tag_name"].startswith("firmware-v"):
        print(r["tag_name"])
        break
')

echo "  keep: $latest_app"
echo "  keep: $latest_fw"

# Build delete list: every release whose tag isn't one of the keepers.
delete_pairs=$(echo "$releases_json" | python3 -c "
import json, sys
data = json.load(sys.stdin)
keep = {'$latest_app', '$latest_fw'}
for r in data:
    if r['tag_name'] not in keep:
        print(f\"{r['id']} {r['tag_name']}\")
")

count=$(echo "$delete_pairs" | grep -c . || true)
echo "→ $count release(s) marked for deletion."

if [[ "$count" -eq 0 ]]; then
    echo "Nothing to do."
    exit 0
fi

if ! $APPLY; then
    echo
    echo "Dry-run. Re-run with --apply to actually delete:"
    echo "$delete_pairs" | awk '{printf "  • %s\n", $2}'
    exit 0
fi

# Apply: delete release, then the tag.
echo
while read -r id tag; do
    [[ -z "$id" ]] && continue
    echo "  deleting release $tag (id=$id)…"
    api -X DELETE "https://api.github.com/repos/${REPO}/releases/${id}" -o /dev/null
    echo "    └ deleting tag refs/tags/$tag…"
    api -X DELETE "https://api.github.com/repos/${REPO}/git/refs/tags/${tag}" -o /dev/null || true
done <<< "$delete_pairs"

echo
echo "Done. Remaining releases:"
api "https://api.github.com/repos/${REPO}/releases?per_page=100" \
    | python3 -c "
import json, sys
for r in json.load(sys.stdin):
    print(f\"  {r['tag_name']}\")"
