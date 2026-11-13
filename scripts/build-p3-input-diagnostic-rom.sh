#!/usr/bin/env bash
# Build CC0 original/derived NDS diagnostic in the official pinned BlocksDS SDK.
# The container has no network, no phone access, and no system privileges.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source="$root/tests/roms/p3-input-diagnostic"
output="$root/app/build/p3-input-diagnostic"
image='skylyrac/blocksds@sha256:c5f19b95d9c2f312e6a68ea244d2ed5120203b4b82af9785937a349096f8e5e4'

mkdir -p "$output/source" "$root/app/build/p3-tmp"
export TMPDIR="$root/app/build/p3-tmp"
cp "$source/Makefile" "$output/Makefile"
cp "$source/source/main.c" "$output/source/main.c"

if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo 'Pinned BlocksDS image not installed. Pull its immutable digest explicitly.' >&2
    exit 1
fi

docker run --rm --network none --read-only --tmpfs /tmp \
    --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=$output,dst=/work" \
    --workdir /work --entrypoint /bin/sh "$image" -c 'make -j2'

rom="$output/p3_input_diagnostic.nds"
test -s "$rom"
sha256sum "$rom"
echo 'Generated real CC0 BlocksDS NDS test cartridge in ignored app/build/.'
