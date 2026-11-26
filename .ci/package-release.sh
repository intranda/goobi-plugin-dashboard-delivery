#!/usr/bin/env bash
# Collects the release assets of a plugin after its build
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

name=${1:-}
if [[ ! $name =~ ^goobi-plugin-([^-]+)- ]]; then
	echo "usage: $0 <plugin name, e.g. goobi-plugin-step-imageqa>" >&2
	exit 1
fi
type=${BASH_REMATCH[1]}

out=target/release-assets
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root=$stage/goobi
rm -rf "$out"
mkdir -p "$out" "$root/lib" "$root/plugins/$type" "$root/plugins/GUI"

# Only the jars in target/ of the root project and its modules: no sources, javadoc or test jars
# and no leftovers of the shade plugin.
mapfile -t jars < <(
	find . -mindepth 2 -maxdepth 3 -type f -name '*.jar' \
		! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' ! -name 'original-*.jar' |
		grep -E '^\./([^/]+/)?target/[^/]+\.jar$' | LC_ALL=C sort || true
)
if [[ ${#jars[@]} -eq 0 ]]; then
	echo "ERROR: no jars found, build the plugin first" >&2
	exit 1
fi

for jar in "${jars[@]}"; do
	module=$(basename "$(dirname "$(dirname "$jar")")")
	case $module in
	# the base module, or a project without module-* directories
	module-base | .) destination=plugins/$type ;;
	module-gui) destination=plugins/GUI ;;
	# module-lib, module-api, module-job, ... are plain libraries
	*) destination=lib ;;
	esac
	echo "$jar -> $destination/"
	cp "$jar" "$root/$destination/"
	cp "$jar" "$out/"
done
# drop the directories that stayed empty (e.g. plugins/GUI)
find "$root" -type d -empty -delete

tar -czf "$out/$name.tar.gz" -C "$stage" goobi

if [[ -d install ]]; then
	find install -maxdepth 1 -type f -exec cp {} "$out/" \;
fi

echo "Release assets in $out:"
ls -1 "$out"
