#!/usr/bin/env bash
#
# release.sh - cut a new Almin release in one command.
#
#   ./release.sh <version> [--client] [--admin]
#
# The server always gets the release version. The two optional flags bump only
# the client artifact that actually changed. Every GitHub release still carries
# all three jars, with unchanged client jars retaining their prior versions, so
# each auto-updater can safely inspect the latest release without forcing an
# unrelated update.
#
# Commit code changes FIRST. This script requires a clean working tree so the
# released jars exactly match the tagged commit.

set -euo pipefail

REPO="TheMin3s/almin"
JAVA_HOME_DIR="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"

cd "$(dirname "$0")"

# --- args -------------------------------------------------------------------
if [ $# -lt 1 ] || [ $# -gt 3 ]; then
  echo "Usage: ./release.sh <version> [--client] [--admin]" >&2
  echo "       Only pass a flag when that client jar changed." >&2
  exit 1
fi
VERSION="$1"
shift
CLIENT_RELEASE=0
ADMIN_RELEASE=0
for arg in "$@"; do
  case "$arg" in
    --client) CLIENT_RELEASE=1 ;;
    --admin)  ADMIN_RELEASE=1 ;;
    *)
      echo "Error: unknown option '$arg'." >&2
      echo "Usage: ./release.sh <version> [--client] [--admin]" >&2
      exit 1
      ;;
  esac
done
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: version '$VERSION' must be X.Y.Z (e.g. 2.37.0)." >&2
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
CURRENT_ADMIN="$(grep '^admin_version=' gradle.properties | cut -d= -f2)"
[ -n "$CURRENT_CLIENT" ] || CURRENT_CLIENT="$CURRENT"
[ -n "$CURRENT_ADMIN" ] || CURRENT_ADMIN="0.0.0"
CLIENT_VERSION="$CURRENT_CLIENT"
ADMIN_VERSION="$CURRENT_ADMIN"
[ "$CLIENT_RELEASE" -eq 1 ] && CLIENT_VERSION="$VERSION"
[ "$ADMIN_RELEASE" -eq 1 ] && ADMIN_VERSION="$VERSION"

if [ "$VERSION" = "$CURRENT" ]; then
  echo "Error: $VERSION is already the current mod_version." >&2
  exit 1
fi
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Error: release $TAG already exists on $REPO." >&2
  exit 1
fi

# Find the commit that shipped one independently-versioned artifact. The path
# checks below catch a forgotten flag before a server can advertise packet/UI
# code that the unchanged jar does not contain.
artifact_base() {
  local artifact_version="$1" artifact_tag base
  artifact_tag="v$artifact_version"
  base="$(git rev-parse -q --verify "refs/tags/$artifact_tag^{commit}" 2>/dev/null || true)"
  if [ -z "$base" ]; then
    base="$(gh release view "$artifact_tag" --repo "$REPO" \
      --json targetCommitish --jq .targetCommitish 2>/dev/null || true)"
  fi
  echo "$base"
}

CLIENT_BASE="$(artifact_base "$CURRENT_CLIENT")"
if [ "$CLIENT_RELEASE" -eq 0 ] && [ -n "$CLIENT_BASE" ] && \
   git cat-file -e "$CLIENT_BASE^{commit}" 2>/dev/null; then
  CLIENT_CHANGES="$(git diff --name-only "$CLIENT_BASE"..HEAD | \
    rg '^src/main/java/com/schecks/almin/client/(AlminClient|AlminNav|ClientConfig|ClientModInstaller|ClientProfileReport|ClientUpdater|DashboardScreen|FileDownloadHandler|ModOfferResultScreen|ModOfferScreen|ServerOutdatedScreen|Text|UpdateAppliedScreen|UpdateRefuseConfirmScreen)\.java$|^src/main/java/com/schecks/almin/(AlminPayloads|UpdateChecker|ServerVersionPayload|DashboardPayload|ModOfferPayload|ModFilePayload|ModFileRequestPayload|ModResponsePayload|ClientProfilePayload|FileTransferPayload)\.java$|^src/main/resources/almin-client-version\.txt$|^build\.gradle$' || true)"
  if [ -n "$CLIENT_CHANGES" ]; then
    echo "Error: base-client files changed since v$CURRENT_CLIENT:" >&2
    echo "$CLIENT_CHANGES" >&2
    echo "Rerun with --client." >&2
    exit 1
  fi
fi

ADMIN_BASE="$(artifact_base "$CURRENT_ADMIN")"
if [ "$ADMIN_RELEASE" -eq 0 ] && [ -n "$ADMIN_BASE" ] && \
   git cat-file -e "$ADMIN_BASE^{commit}" 2>/dev/null; then
  ADMIN_CHANGES="$(git diff --name-only "$ADMIN_BASE"..HEAD | \
    rg '^src/main/java/com/schecks/almin/client/(AlminAdminClient|ActivityScreen|ConsoleScreen|DirBrowserScreen|EntryContextScreen|NanoEditorScreen|PanelScreen|RenameFileScreen|WebPanelScreen)\.java$|^src/main/java/com/schecks/almin/(AdminPayloads|AdminVersionPayload|ActivityPayload|ActivityRequestPayload|ConsoleLinesPayload|ConsoleOpenPayload|ConsoleSubscribePayload|DirListingPayload|DirRequestPayload|FileUploadPayload|NanoOpenPayload|NanoSavePayload|PanelPayload|WebAdminPayload|WebAdminRequestPayload|WebControlPayload|WebPasswordPayload)\.java$|^src/main/resources/almin-admin-version\.txt$|^build\.gradle$' || true)"
  if [ -n "$ADMIN_CHANGES" ]; then
    echo "Error: admin-extension files changed since v$CURRENT_ADMIN:" >&2
    echo "$ADMIN_CHANGES" >&2
    echo "Rerun with --admin." >&2
    exit 1
  fi
fi

echo "Releasing server: $CURRENT -> $VERSION"
echo "Base client:      $CURRENT_CLIENT -> $CLIENT_VERSION"
echo "Admin extension:  $CURRENT_ADMIN -> $ADMIN_VERSION"

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

# --- bump versions ----------------------------------------------------------
sed -i '' "s/^mod_version=.*/mod_version=$VERSION/" gradle.properties
sed -i '' "s/^client_version=.*/client_version=$CLIENT_VERSION/" gradle.properties
sed -i '' "s/^admin_version=.*/admin_version=$ADMIN_VERSION/" gradle.properties

# --- build ------------------------------------------------------------------
export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Building..."
./gradlew build

# These classifiers are the updater protocol: each side selects only its own
# asset, then reads the independent version from that asset's filename.
SERVER_JAR="build/libs/almin-$VERSION-server.jar"
CLIENT_JAR="build/libs/almin-$CLIENT_VERSION-client.jar"
ADMIN_JAR="build/libs/almin-$ADMIN_VERSION-admin.jar"
verify_jar() {
  local jar_path="$1" expected_version="$2" expected_id="$3" declared id
  [ -f "$jar_path" ] || { echo "Error: built jar not found at $jar_path" >&2; exit 1; }
  declared="$(unzip -p "$jar_path" fabric.mod.json | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' | head -1)"
  id="$(unzip -p "$jar_path" fabric.mod.json | sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' | head -1)"
  if [ "$declared" != "$expected_version" ] || [ "$id" != "$expected_id" ]; then
    echo "Error: $jar_path declares id/version '$id/$declared'; expected '$expected_id/$expected_version'." >&2
    exit 1
  fi
  echo "Built $jar_path ($id $declared)"
}
verify_jar "$SERVER_JAR" "$VERSION" "almin"
verify_jar "$CLIENT_JAR" "$CLIENT_VERSION" "almin"
verify_jar "$ADMIN_JAR" "$ADMIN_VERSION" "almin_admin"

# --- commit + push ----------------------------------------------------------
git add gradle.properties
git commit -m "Release $TAG"
COMMITTED=1

PUSH_REMOTE="$(git remote -v | awk -v repo="$REPO" '$3=="(push)" && index($2, repo) {print $1; exit}')"
if [ -z "$PUSH_REMOTE" ]; then
  echo "Error: no git remote points at $REPO. Add one:" >&2
  echo "  git remote add almin https://github.com/$REPO.git" >&2
  exit 1
fi
if ! git push "$PUSH_REMOTE" HEAD:main; then
  echo "Commit created locally but push failed. Fix auth/network, then: git push $PUSH_REMOTE HEAD:main" >&2
  exit 1
fi
TARGET="$(git rev-parse HEAD)"

# --- publish the GitHub release ---------------------------------------------
if ! gh release create "$TAG" "$SERVER_JAR" "$CLIENT_JAR" "$ADMIN_JAR" \
      --repo "$REPO" \
      --target "$TARGET" \
      --title "Almin $VERSION" \
      --generate-notes; then
  echo "Bump pushed, but the release step failed. Retry with:" >&2
  echo "  gh release create $TAG $SERVER_JAR $CLIENT_JAR $ADMIN_JAR --repo $REPO --target $TARGET --title \"Almin $VERSION\" --generate-notes" >&2
  exit 1
fi

echo
echo "Released $TAG -> https://github.com/$REPO/releases/tag/$TAG"
