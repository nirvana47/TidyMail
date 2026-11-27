#!/bin/sh
# Run on the developer Mac. Deny installed package-manager files so they cannot
# hide missing runtime resources. Uses only disposable data; never a mailbox.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
app=${1:-"$root/desktop-dist/stage/TidyMail.app"}
exec /usr/bin/sandbox-exec -p '(version 1)(allow default)(deny file-read* (subpath "/opt/homebrew") (subpath "/usr/local"))' \
    "$app/Contents/MacOS/TidyMail" --smoke-test
