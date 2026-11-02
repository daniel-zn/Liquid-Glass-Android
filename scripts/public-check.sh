#!/bin/sh
# Public-repository hygiene: fails if a tracked file or a commit message being integrated matches one of
# the maintainer's private patterns (one extended regex per line in .mercurius/public-check.local, an
# untracked file in the main checkout; not part of the repository). Without that file it passes.
set -eu
patterns="${LAND_SOURCE:-.}/.mercurius/public-check.local"
[ -f "$patterns" ] || { echo "public-check: no local pattern file, nothing to check"; exit 0; }
bad=0
if git grep -I -n -i -E -f "$patterns" -- . ':!scripts/public-check.sh' >/tmp/public-check.$$ 2>/dev/null; then
  echo "public-check: private text in tracked files:"; cut -c1-160 /tmp/public-check.$$; bad=1
fi
range="${LAND_BASE:+$LAND_BASE..}${LAND_HEAD:-HEAD}"
if git log --format=%B "$range" | grep -i -E -f "$patterns" >/dev/null 2>&1; then
  echo "public-check: private text in a commit message ($range)"; bad=1
fi
rm -f /tmp/public-check.$$
[ $bad = 0 ] && echo "public-check: clean"
exit $bad
