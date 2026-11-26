#!/usr/bin/env bash
# Creates the GitHub release for the version written in the build files. Runs in CI after the tests, the
# builds and the Android start-up check: it only collects what they produced.
#
#   MODE=draft|publish            (a tag push always publishes)
#   HAS_KEY=true|false            the APKs were signed with the release key from the repository secrets
#   TEMPORARY_KEY_OK=true|false   allow a release signed with CI's throw-away debug key
#   dist/android, dist/windows    the build artifacts
set -euo pipefail

fail() { echo "::error::$1"; exit 1; }

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' platform/android/build.gradle.kts | head -1)
DESKTOP_VERSION=$(sed -n 's/^val appVersion = "\(.*\)"/\1/p' platform/desktop/build.gradle.kts | head -1)
[ -n "$VERSION" ] || fail "versionName not found in platform/android/build.gradle.kts"
[ "$VERSION" = "$DESKTOP_VERSION" ] || fail "Android is $VERSION but Windows is $DESKTOP_VERSION: set the same version in both build files"
TAG="v$VERSION"
MODE="${MODE:-draft}"
if [[ "${GITHUB_REF:-}" == refs/tags/* ]]; then
  [ "${GITHUB_REF#refs/tags/}" = "$TAG" ] || fail "Tag ${GITHUB_REF#refs/tags/} does not match the version in the build files ($VERSION)"
  MODE=publish
fi
NOTES="docs/releases/$TAG.md"
[ -f "$NOTES" ] || fail "Write the release notes first: $NOTES"

# An APK signed with a key that exists only on this runner can never be updated: Android refuses a newer
# build signed with another key. Such a release has to be asked for explicitly.
if [ "${HAS_KEY:-false}" != "true" ] && [ "${TEMPORARY_KEY_OK:-false}" != "true" ]; then
  fail "No signing key: add the secrets GRAPHITE_KEYSTORE_BASE64, GRAPHITE_KEYSTORE_PASSWORD, GRAPHITE_KEY_ALIAS and GRAPHITE_KEY_PASSWORD (see README), or run the workflow with 'temporary_key'."
fi

OUT=release-out
mkdir -p "$OUT"
for abi in arm64-v8a armeabi-v7a x86_64 universal; do
  src="dist/android/release/android-$abi-release.apk"
  [ -f "$src" ] || fail "Missing $src"
  cp "$src" "$OUT/Graphite-$VERSION-android-$abi.apk"
done
msi=$(ls dist/windows/*.msi 2>/dev/null | head -1)
[ -n "$msi" ] || fail "Missing the MSI"
cp "$msi" "$OUT/Graphite-$VERSION-windows-x64.msi"
(cd "$OUT" && sha256sum Graphite-* > SHA256SUMS.txt)

# Who signed the APKs: lets anyone check that an update comes from the same publisher.
fingerprint=""
apksigner=$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/ 2>/dev/null | sort -V | tail -1)apksigner
if [ -x "$apksigner" ]; then
  for apk in "$OUT"/*.apk; do
    fp=$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
    [ -n "$fp" ] || fail "$apk is not signed"
    [ -z "$fingerprint" ] || [ "$fingerprint" = "$fp" ] || fail "The APKs are signed with different keys"
    fingerprint="$fp"
  done
fi

BODY=release-notes.md
{
  cat "$NOTES"
  echo
  echo "## Проверка файлов"
  echo
  echo "Контрольные суммы SHA-256 — в файле \`SHA256SUMS.txt\`."
  if [ -n "$fingerprint" ]; then
    echo "Отпечаток сертификата, которым подписаны APK: \`$fingerprint\`."
  fi
  if [ "${HAS_KEY:-false}" != "true" ]; then
    echo
    echo "> APK этого выпуска подписаны временным ключом. Следующую версию нельзя будет установить поверх: сначала придётся удалить эту."
  fi
} > "$BODY"

# A draft of the same version is replaced; a published release is never overwritten.
existing=$(gh api "repos/$GITHUB_REPOSITORY/releases?per_page=100" --jq ".[] | select(.tag_name==\"$TAG\") | \"\(.id) \(.draft)\"")
while read -r id draft; do
  [ -n "$id" ] || continue
  [ "$draft" = "true" ] || fail "Release $TAG is already published. Raise the version in both build files for a new release."
  gh api -X DELETE "repos/$GITHUB_REPOSITORY/releases/$id"
done <<< "$existing"

args=(--title "Graphite $VERSION" --notes-file "$BODY" --target "$GITHUB_SHA")
[ "$MODE" = "draft" ] && args+=(--draft)
gh release create "$TAG" "${args[@]}" "$OUT"/*
echo "Release $TAG created ($MODE)"
