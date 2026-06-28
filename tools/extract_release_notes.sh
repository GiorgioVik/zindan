#!/usr/bin/env bash
# Build GitHub Release body for tag vX.Y.Z-CODE.
# Priority: RELEASE_NOTES_<tag>.md → CHANGELOG.md section (CODE) → exit 1
set -euo pipefail

TAG="${1:?usage: extract_release_notes.sh v1.5.4-243 [owner/repo]}"
REPO="${2:-GiorgioVik/zindan}"
CODE="${TAG##*-}"
VERSION=$(echo "${TAG}" | sed 's/^v//' | sed 's/-[^-]*$//')
NOTES_FILE="RELEASE_NOTES_${TAG}.md"

if [[ -f "${NOTES_FILE}" ]]; then
  cat "${NOTES_FILE}"
  exit 0
fi

if [[ ! -f CHANGELOG.md ]]; then
  echo "CHANGELOG.md not found" >&2
  exit 1
fi

SECTION=$(
  awk -v code="${CODE}" '
    BEGIN { n = 0 }
    $0 ~ "^[0-9]+\\.[0-9]+\\.[0-9]+ \\(" code "\\)" { n = 1; next }
    n && /^[0-9]+\\.[0-9]+\\.[0-9]+ \\([0-9]+\\)/ { exit }
    n { print }
  ' CHANGELOG.md
)

if [[ -z "${SECTION}" ]]; then
  echo "No CHANGELOG section for build (${CODE})" >&2
  exit 1
fi

{
  echo "## Zindan ${VERSION} (${CODE})"
  echo ""
  echo "${SECTION}" | sed '/^===/d'
  echo ""
  echo "### Install"
  echo ""
  echo "Download the APK below. Update over an existing install in **both** profiles (personal first, then work) — no need to recreate the work profile when \`versionCode\` increases."
  echo ""
  echo "See [CHANGELOG.md](https://github.com/${REPO}/blob/main/CHANGELOG.md) and [USER_GUIDE.md](https://github.com/${REPO}/blob/main/USER_GUIDE.md)."
}
