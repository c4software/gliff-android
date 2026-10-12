#!/bin/sh
# Downloads Shared.xcframework, the Kotlin side of the iOS app, into
# iosApp/Frameworks. It is built by the "iOS framework" workflow, on a Mac:
# Kotlin cannot link it under Linux. Needs the gh command, logged in.
#
#   scripts/fetch-ios-framework.sh            the last successful run
#   scripts/fetch-ios-framework.sh <run id>   that run
#
# To build one from a commit first, tag it ios-something and push the tag:
#   git tag ios-try-1 && git push origin ios-try-1
set -eu
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"

run="${1:-$(gh run list --workflow ios-framework.yml --status success --limit 1 --json databaseId --jq '.[0].databaseId // empty')}"
if [ -z "$run" ]; then
  echo "No successful run of the iOS framework workflow yet." >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" --name Shared.xcframework --dir "$tmp"

mkdir -p iosApp/Frameworks
rm -rf iosApp/Frameworks/Shared.xcframework
unzip -q "$tmp/Shared.xcframework.zip" -d iosApp/Frameworks
echo "iosApp/Frameworks/Shared.xcframework is the one of run $run:"
gh run view "$run" --json headBranch,headSha,createdAt --jq '"  \(.headBranch) \(.headSha[0:7]) \(.createdAt)"'
