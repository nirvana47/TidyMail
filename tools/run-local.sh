#!/bin/sh
# Normal local operation: sync and local recommendations run; paid workers stay opt-in.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
exec /usr/bin/java -Dspring.devtools.restart.enabled=false \
  -jar target/tidymail-0.1.0.jar \
  --spring.task.scheduling.enabled=true \
  --spring.flyway.enabled=true \
  --app.cleanup.worker.enabled=true \
  --app.recommendations.worker.enabled=true \
  --app.classification.worker.enabled=false \
  --app.labeling.worker.enabled=false \
  --app.classification.benchmark.enabled=false \
  "$@"
