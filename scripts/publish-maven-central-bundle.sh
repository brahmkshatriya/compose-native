#!/usr/bin/env bash
set -euo pipefail

compose_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="${1:?Usage: publish-maven-central-bundle.sh REPOSITORY VERSION [BUNDLE]}"
version="${2:?Usage: publish-maven-central-bundle.sh REPOSITORY VERSION [BUNDLE]}"
bundle="${3:-$compose_root/build/central/compose-native-$version.zip}"
properties_file="${GRADLE_PROPERTIES_FILE:-$HOME/.gradle/gradle.properties}"
repository="$(realpath "$repository")"
bundle="$(realpath -m "$bundle")"

if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid Maven version: $version" >&2
    exit 1
fi
if [[ ! -f "$properties_file" ]]; then
    echo "Gradle properties file does not exist: $properties_file" >&2
    exit 1
fi

read_property() {
    local property_name="$1"
    awk -v key="$property_name" \
        'index($0, key "=") == 1 { value = substr($0, length(key) + 2) } END { print value }' \
        "$properties_file" | tr -d '\r'
}

central_user="$(read_property mavenCentralUsername)"
central_password="$(read_property mavenCentralPassword)"
signing_key_id="$(read_property signing.keyId)"
signing_password="$(read_property signing.password)"
signing_keyring="$(read_property signing.secretKeyRingFile)"

for required_value in "$central_user" "$central_password" "$signing_password" "$signing_keyring"; do
    if [[ -z "$required_value" ]]; then
        echo "Required Central or signing property is missing from $properties_file" >&2
        exit 1
    fi
done
if [[ ! -s "$signing_keyring" ]]; then
    echo "Signing key ring does not exist or is empty: $signing_keyring" >&2
    exit 1
fi

bundle_directory="$(dirname "$bundle")"
staging="$bundle_directory/staging"
mkdir -p "$bundle_directory"

python3 "$compose_root/scripts/prepare-maven-central-bundle.py" \
    --repository "$repository" \
    --staging "$staging" \
    --version "$version"

gpg_home="$bundle_directory/gpg"
mkdir -p "$gpg_home"
chmod 700 "$gpg_home"
export GNUPGHOME="$gpg_home"
gpg --batch --quiet --import "$signing_keyring"

while IFS= read -r -d '' artifact; do
    signature="$artifact.asc"
    gpg_args=(
        --batch
        --yes
        --quiet
        --armor
        --detach-sign
        --pinentry-mode loopback
        --passphrase-fd 0
        --output "$signature"
    )
    if [[ -n "$signing_key_id" ]]; then
        gpg_args+=(--local-user "$signing_key_id")
    fi
    printf '%s' "$signing_password" | gpg "${gpg_args[@]}" "$artifact"
    md5sum "$artifact" | awk '{print $1}' > "$artifact.md5"
    sha1sum "$artifact" | awk '{print $1}' > "$artifact.sha1"
    sha256sum "$artifact" | awk '{print $1}' > "$artifact.sha256"
    sha512sum "$artifact" | awk '{print $1}' > "$artifact.sha512"
done < <(
    find "$staging" -type f \
        ! -name '*.asc' \
        ! -name '*.md5' \
        ! -name '*.sha1' \
        ! -name '*.sha256' \
        ! -name '*.sha512' \
        -print0 | sort -z
)

pushd "$staging" >/dev/null
python3 -m zipfile -c "$bundle" ./*
popd >/dev/null
bundle_size="$(stat -c '%s' "$bundle")"
if (( bundle_size >= 1000000000 )); then
    echo "Central bundle is too large: $bundle_size bytes" >&2
    exit 1
fi

authorization="$(printf '%s:%s' "$central_user" "$central_password" | base64 | tr -d '\r\n')"
deployment_id="$(
    curl --fail-with-body --silent --show-error \
        --request POST \
        --header "Authorization: Bearer $authorization" \
        --form "bundle=@$bundle;type=application/octet-stream" \
        "https://central.sonatype.com/api/v1/publisher/upload?name=compose-native-$version&publishingType=AUTOMATIC"
)"
if [[ ! "$deployment_id" =~ ^[0-9a-fA-F-]{36}$ ]]; then
    echo "Central returned an invalid deployment ID" >&2
    exit 1
fi
echo "Uploaded Central deployment $deployment_id"

status_url="https://central.sonatype.com/api/v1/publisher/status?id=$deployment_id"
timeout_seconds="${CENTRAL_PUBLISH_TIMEOUT_SECONDS:-3600}"
poll_seconds="${CENTRAL_PUBLISH_POLL_SECONDS:-15}"
deadline=$((SECONDS + timeout_seconds))
last_state=""

while (( SECONDS < deadline )); do
    status_json="$(
        curl --fail-with-body --silent --show-error \
            --request POST \
            --header "Authorization: Bearer $authorization" \
            "$status_url"
    )"
    deployment_state="$(
        python3 -c 'import json,sys; print(json.load(sys.stdin).get("deploymentState", ""))' \
            <<<"$status_json"
    )"
    if [[ -z "$deployment_state" ]]; then
        echo "Central status response did not contain deploymentState" >&2
        printf '%s\n' "$status_json" >&2
        exit 1
    fi
    if [[ "$deployment_state" != "$last_state" ]]; then
        echo "Central deployment $deployment_id: $deployment_state"
        last_state="$deployment_state"
    fi

    case "$deployment_state" in
        PUBLISHED)
            echo "Central published deployment $deployment_id"
            exit 0
            ;;
        FAILED)
            echo "Central deployment $deployment_id failed validation or publication" >&2
            python3 -c '
import json, sys
data = json.load(sys.stdin)
errors = data.get("errors")
if errors:
    print(json.dumps(errors, indent=2), file=sys.stderr)
else:
    print(json.dumps(data, indent=2), file=sys.stderr)
' <<<"$status_json"
            exit 1
            ;;
        PENDING|VALIDATING|VALIDATED|PUBLISHING)
            sleep "$poll_seconds"
            ;;
        *)
            echo "Unexpected Central deployment state: $deployment_state" >&2
            printf '%s\n' "$status_json" >&2
            exit 1
            ;;
    esac
done

echo "Timed out waiting ${timeout_seconds}s for Central deployment $deployment_id" >&2
exit 1
