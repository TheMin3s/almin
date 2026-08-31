#!/usr/bin/env bash
#
# release.sh - cut a new Almin release in one command.
#
#   ./release.sh <version> [--client]   e.g.  ./release.sh 1.1.0 --client
#
# What it does:
#   1. Bumps mod_version in gradle.properties
#   2. Builds the jar
#   3. Commits + pushes the version bump
#   4. Publishes a GitHub release (tag vX.Y.Z) with the jar attached
#      and auto-generated notes
#
# Commit your code changes FIRST. This script requires a clean working tree
# so the released jar exactly matches the tagged commit.

set -euo pipefail

REPO="TheMin3s/almin"
JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"

cd "$(dirname "$0")"

# --- args -------------------------------------------------------------------
if [ $# -lt 1 ] || [ $# -gt 2 ] || { [ $# -eq 2 ] && [ "$2" != "--client" ]; }; then
  echo "Usage: ./release.sh <version> [--client]" >&2
  echo "       --client bumps the client build too; omit it for server-only changes." >&2
  exit 1
fi
VERSION="$1"
CLIENT_RELEASE=0
[ "${2:-}" = "--client" ] && CLIENT_RELEASE=1
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: version '$VERSION' must be X.Y.Z (e.g. 1.1.0)." >&2
  exit 1
fi
TAG="v$VERSION"

# --- preflight checks -------------------------------------------------------
command -v gh >/dev/null || { echo "Error: gh (GitHub CLI) is not installed." >&2; exit 1; }
[ -d "$JAVA_HOME_DIR" ]  || { echo "Error: JDK not found at $JAVA_HOME_DIR (set JAVA_HOME)." >&2; exit 1; }
[ -f gradle.properties ] || { echo "Error: run this from the project root." >&2; exit 1; }

if [ -n "$(git status --porcelain)" ]; then
  echo "Error: uncommitted changes present. Commit your code first, then release." >&2
  git status --short >&2
  exit 1
fi

CURRENT="$(grep '^mod_version=' gradle.properties | cut -d= -f2)"
CURRENT_CLIENT="$(grep '^client_version=' gradle.properties | cut -d= -f2)"
[ -n "$CURRENT_CLIENT" ] || CURRENT_CLIENT="$CURRENT"
CLIENT_VERSION="$CURRENT_CLIENT"
[ "$CLIENT_RELEASE" -eq 1 ] && CLIENT_VERSION="$VERSION"
if [ "$VERSION" = "$CURRENT" ]; then
  echo "Error: $VERSION is already the current mod_version." >&2
  exit 1
fi
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Error: release $TAG already exists on $REPO." >&2
  exit 1
fi

# A forgotten --client is worse than an unnecessary prompt: servers could
# start speaking a packet shape the old client cannot decode. Compare against
# the release that introduced the currently declared client build and stop if
# client/runtime inputs changed without being acknowledged.
CLIENT_BASE_TAG="v$CURRENT_CLIENT"
CLIENT_BASE="$(git rev-parse -q --verify "refs/tags/$CLIENT_BASE_TAG^{commit}" 2>/dev/null || true)"
if [ -z "$CLIENT_BASE" ]; then
  CLIENT_BASE="$(gh release view "$CLIENT_BASE_TAG" --repo "$REPO" \
    --json targetCommitish --jq .targetCommitish 2>/dev/null || true)"
fi
if [ "$CLIENT_RELEASE" -eq 0 ] && [ -n "$CLIENT_BASE" ] && \
   git cat-file -e "$CLIENT_BASE^{commit}" 2>/dev/null; then
  CLIENT_CHANGES="$(git diff --name-only "$CLIENT_BASE"..HEAD | \
    rg '^src/main/java/com/schecks/almin/client/|^src/main/java/com/schecks/almin/(AlminPayloads|.*Payload)\.java$|^src/main/java/com/schecks/almin/UpdateChecker\.java$|^build\.gradle$' || true)"
  if [ -n "$CLIENT_CHANGES" ]; then
    echo "Error: client-facing files changed since $CLIENT_BASE_TAG:" >&2
    echo "$CLIENT_CHANGES" >&2
    echo "Rerun with --client if clients need this release." >&2
    exit 1
  fi
fi

echo "Releasing $CURRENT -> $VERSION"
echo "Client build: $CURRENT_CLIENT -> $CLIENT_VERSION"

# --- revert the version bump if anything fails before the commit ------------
COMMITTED=0
cleanup() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$COMMITTED" -eq 0 ]; then
    echo "Failed (exit $code) - reverting gradle.properties." >&2
    git checkout -- gradle.properties 2>/dev/null || true
  fi
}
trap cleanup EXIT

# --- bump version -----------------------------------------------------------
sed -i '' "s/^mod_version=.*/mod_version=$VERSION/" gradle.properties
sed -i '' "s/^client_version=.*/client_version=$CLIENT_VERSION/" gradle.properties
echo "Bumped mod_version: $CURRENT -> $VERSION"

# --- build ------------------------------------------------------------------
export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Building..."
./gradlew build

# A release ships two jars. The updaters pick theirs by the "server"/"client"
# in the filename, so these names are load-bearing — don't rename them without
# changing UpdateChecker.SERVER_JAR / CLIENT_JAR too.
SERVER_JAR="build/libs/almin-$VERSION-server.jar"
CLIENT_JAR="build/libs/almin-$CLIENT_VERSION-client.jar"
verify_jar() {
  local j="$1" expected="$2"
  [ -f "$j" ] || { echo "Error: built jar not found at $j" >&2; exit 1; }
  # The filename is not proof of the contents. A jar whose fabric.mod.json
  # disagrees with the tag makes the mod report the wrong version, which sends
  # the auto-updater into a loop: update, restart, still look outdated, repeat.
  DECLARED="$(unzip -p "$j" fabric.mod.json | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' | head -1)"
  if [ "$DECLARED" != "$expected" ]; then
    echo "Error: $j declares version '$DECLARED' but expected $expected." >&2
    echo "       Refusing to publish a jar that misreports its own version." >&2
    exit 1
  fi
  echo "Built $j (declares $DECLARED)"
}
verify_jar "$SERVER_JAR" "$VERSION"
verify_jar "$CLIENT_JAR" "$CLIENT_VERSION"

# --- commit + push ----------------------------------------------------------
git add gradle.properties
git commit -m "Release $TAG"
COMMITTED=1

# Push to whichever remote actually points at $REPO — "origin" may still be an
# older repo, and pushing the release commit somewhere the tag isn't would
# publish a release whose source commit is missing.
PUSH_REMOTE="$(git remote -v | awk -v repo="$REPO" '$3=="(push)" && index($2, repo) {print $1; exit}')"
if [ -z "$PUSH_REMOTE" ]; then
  echo "Error: no git remote points at $REPO. Add one:" >&2
  echo "  git remote add almin https://github.com/$REPO.git" >&2
  exit 1
fi
if ! git push "$PUSH_REMOTE" HEAD; then
  echo "Commit created locally but push failed. Fix auth/network, then: git push $PUSH_REMOTE HEAD" >&2
  exit 1
fi
# Tag the exact commit that was just built and pushed, not the repo's default branch.
TARGET="$(git rev-parse HEAD)"

# --- publish the GitHub release ---------------------------------------------
if ! gh release create "$TAG" "$SERVER_JAR" "$CLIENT_JAR" \
      --repo "$REPO" \
      --target "$TARGET" \
      --title "Almin $VERSION" \
      --generate-notes; then
  echo "Bump pushed, but the release step failed. Retry with:" >&2
  echo "  gh release create $TAG $SERVER_JAR $CLIENT_JAR --repo $REPO --title \"Almin $VERSION\" --generate-notes" >&2
  exit 1
fi

echo
echo "Released $TAG -> https://github.com/$REPO/releases/tag/$TAG"
