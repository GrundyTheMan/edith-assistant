#!/usr/bin/env bash
# One-time: create Edith's release signing key in ~/.edith/ (outside the repo).
#
#   ~/.edith/edith-release.jks        the key
#   ~/.edith/keystore.properties      its location and generated password (read by app/build.gradle.kts)
#
# BACK UP THE WHOLE ~/.edith FOLDER (e.g. to a USB stick or password manager). Every update of the
# installed app must be signed with this same key; if it's lost, phones have to uninstall Edith (losing
# her data) before a build signed with a new key will install.
source "$(dirname "$0")/lib.sh"

KEYSTORE="$KEY_DIR/edith-release.jks"
ALIAS="edith"

[ -e "$KEYSTORE" ] && die "$KEYSTORE already exists. Refusing to overwrite a signing key."
[ -e "$KEYSTORE_PROPERTIES" ] && die "$KEYSTORE_PROPERTIES already exists. Refusing to overwrite it."

KEYTOOL="keytool"
if [ -n "${JAVA_HOME:-}" ]; then
  if [ "$IS_WINDOWS" = 1 ]; then KEYTOOL="$(cygpath -u "$JAVA_HOME")/bin/keytool.exe"; else KEYTOOL="$JAVA_HOME/bin/keytool"; fi
fi

mkdir -p "$KEY_DIR"
PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32)"

# Gradle reads the path from a .properties file: use forward slashes (C:/Users/... on Windows).
if [ "$IS_WINDOWS" = 1 ]; then STORE_PATH="$(cygpath -m "$KEYSTORE")"; else STORE_PATH="$KEYSTORE"; fi

step "Creating $KEYSTORE (RSA 4096, valid 10,000 days)"
"$KEYTOOL" -genkeypair -keystore "$STORE_PATH" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Edith" \
  -storepass "$PASSWORD" -keypass "$PASSWORD" >/dev/null

# PKCS12 keystores use one password for the store and the key.
cat > "$KEYSTORE_PROPERTIES" <<EOF
# Edith release signing. Secret: never commit, never paste into chats or logs. Back up with the .jks.
storeFile=$STORE_PATH
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF
chmod 600 "$KEYSTORE_PROPERTIES" "$KEYSTORE" 2>/dev/null || true

step "Certificate fingerprints (not secret; the SHA-1 is needed for Google sign-in, chunk C17)"
FINGERPRINTS="$("$KEYTOOL" -list -v -keystore "$STORE_PATH" -storepass "$PASSWORD" -alias "$ALIAS")"
echo "$FINGERPRINTS" | grep -E "SHA1:|SHA256:"
# scripts/release.sh checks every APK against this, so a build signed with any other key is caught.
echo "releaseCertSha256=$(echo "$FINGERPRINTS" | sed -n 's/.*SHA256: //p' | tr -d ':\r' | tr 'A-F' 'a-f')" >> "$KEYSTORE_PROPERTIES"

echo
echo "Done. Now back up the folder $KEY_DIR — losing it means installed copies of Edith can't be updated."
