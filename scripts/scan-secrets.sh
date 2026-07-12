#!/usr/bin/env bash
set -euo pipefail

readonly GITLEAKS_IMAGE="ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly repository_root

run_gitleaks() {
    docker run --rm \
        --volume "${1}:/repo:ro" \
        --workdir /repo \
        "$GITLEAKS_IMAGE" \
        git --config=/repo/.gitleaks.toml --log-opts="--all" \
        --no-banner --redact --exit-code=1 /repo
}

run_gitleaks "$repository_root"

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT
cp "$repository_root/.gitleaks.toml" "$fixture_root/.gitleaks.toml"
printf 'Grpc-Metadata-macaroon: %064d\n' 0 > "$fixture_root/macaroon.fixture"
git -C "$fixture_root" init --quiet
git -C "$fixture_root" add .
git -C "$fixture_root" -c user.name=secret-scan -c user.email=secret-scan@example.invalid commit --quiet -m fixture

if run_gitleaks "$fixture_root"; then
    echo "Synthetic LND macaroon fixture was not detected." >&2
    exit 1
fi

echo "Secret scan passed; the synthetic LND macaroon fixture was detected with redaction."
