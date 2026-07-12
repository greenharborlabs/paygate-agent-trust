#!/usr/bin/env bash
set -euo pipefail

tag="${1:?release tag is required}"
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release tag must match vX.Y.Z: $tag" >&2
  exit 1
fi
version="$(sed -n 's/^version=//p' gradle.properties)"
if [[ -z "$version" || "$version" == *-SNAPSHOT || "v$version" != "$tag" ]]; then
  echo "gradle.properties version '$version' must be a non-SNAPSHOT match for '$tag'." >&2
  exit 1
fi
git fetch origin master --quiet
if ! git merge-base --is-ancestor HEAD origin/master; then
  echo "Tagged commit must already be contained in origin/master." >&2
  exit 1
fi
