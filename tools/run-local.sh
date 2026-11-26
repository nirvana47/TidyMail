#!/bin/sh
# Normal local operation: mailbox sync, local labels and user-confirmed cleanup.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
# Keep the running archive outside target/: Maven can replace or clean that tree.
# Reuse identical builds, but never overwrite an archive a JVM may have open.
build_jar=target/tidymail-0.1.0.jar
build_hash=$(shasum -a 256 "$build_jar" | cut -d ' ' -f 1)
runtime_dir="$project_dir/.local-runtime"
runtime_jar="$runtime_dir/tidymail-$build_hash.jar"
mkdir -p "$runtime_dir"
if [ ! -f "$runtime_jar" ]; then
  staged_jar=$(mktemp "$runtime_dir/.staged.XXXXXX")
  trap 'rm -f "$staged_jar"' EXIT
  cp "$build_jar" "$staged_jar"
  staged_hash=$(shasum -a 256 "$staged_jar" | cut -d ' ' -f 1)
  if [ "$staged_hash" != "$build_hash" ]; then
    echo "Build changed during startup; wait for the build to finish and retry." >&2
    exit 1
  fi
  # A concurrent launcher may have already published this identical archive.
  ln "$staged_jar" "$runtime_jar" 2>/dev/null || test -f "$runtime_jar"
  rm -f "$staged_jar"
  trap - EXIT
fi
exec /usr/bin/java -Dspring.devtools.restart.enabled=false \
  -jar "$runtime_jar" \
  --spring.task.scheduling.enabled=true \
  --spring.flyway.enabled=true \
  --app.cleanup.worker.enabled=true \
  "$@"
