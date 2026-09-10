#!/usr/bin/env bash
#
# build.sh — fetch deps, compile, and package otel-jfr-extension.jar
#
# No Gradle or Maven required: only a JDK (javac + jar) and curl.
#
# Two dependency classes:
#
#   COMPILE-ONLY (not bundled): OpenTelemetry API/SDK + javaagent extension API.
#     The agent provides these at runtime (relocated when it loads an
#     extension); bundling them would shadow the agent's copies and break it.
#
#   BUNDLED (fat-jar'd in): opentelemetry-proto only. Needed for the OTLP
#     *profiles* signal (otel.instrumentation.jfr.profile=true) -- the agent
#     ships its own marshaler and none of the generated proto message classes.
#     protobuf-java is NOT bundled: the agent already provides com.google.protobuf
#     (unshaded) at runtime, and it wins on the classloader, so we compile against
#     it but must NOT ship our own copy. Critically, the bundled proto gencode
#     version must be <= the agent's protobuf runtime, or protobuf's RuntimeVersion
#     check fails at class init. opentelemetry-proto 1.10.0-alpha uses gencode
#     4.34.0, compatible with the agent's protobuf 4.35.0 runtime.
#
set -euo pipefail

cd "$(dirname "$0")"

# Must match the SDK version bundled in your opentelemetry-javaagent.jar:
#   unzip -p opentelemetry-javaagent.jar \
#     io/opentelemetry/javaagent/shaded/io/opentelemetry/api/version.properties
OTEL_VERSION="${OTEL_VERSION:-1.64.0}"
AGENT_VERSION="${AGENT_VERSION:-2.30.0}"
AGENT_EXT_API_VERSION="${AGENT_EXT_API_VERSION:-${AGENT_VERSION}-alpha}"
# OTLP profiles is Alpha / "development". Its gencode must be <= the protobuf
# runtime the agent ships. Agent 2.30.0 ships protobuf 4.35.0, so use a proto
# build with gencode <= that (1.10.0-alpha -> 4.34.0). protobuf-java is compile-
# only (provided by the agent at runtime), matched to that gencode.
OTEL_PROTO_VERSION="${OTEL_PROTO_VERSION:-1.10.0-alpha}"
PROTOBUF_VERSION="${PROTOBUF_VERSION:-4.34.0}"

MAVEN_CENTRAL="https://repo1.maven.org/maven2"
LIBS_DIR="libs"
BUILD_DIR="build/classes"

# --- Versioning -----------------------------------------------------------
# Base version (override with VERSION=x.y.z). Each successful build bumps a
# zero-padded, monotonically increasing build number persisted in
# .build-number, producing artifacts like otel-jfr-extension-0.0.1-001.jar.
# A stable, unversioned otel-jfr-extension.jar copy is also written so the
# -Dotel.javaagent.extensions=... path documented in the README stays valid.
VERSION="${VERSION:-0.0.1}"
ARTIFACT_BASE="otel-jfr-extension"
BUILD_NUMBER_FILE=".build-number"

prev_build="$([[ -f "${BUILD_NUMBER_FILE}" ]] && cat "${BUILD_NUMBER_FILE}" || echo 0)"
BUILD_NUMBER="$((prev_build + 1))"
BUILD_LABEL="$(printf '%03d' "${BUILD_NUMBER}")"
FULL_VERSION="${VERSION}-${BUILD_LABEL}"

OUT_JAR="${ARTIFACT_BASE}-${FULL_VERSION}.jar"
STABLE_JAR="${ARTIFACT_BASE}.jar"

COMPILE_ONLY_ARTIFACTS=(
  "io/opentelemetry/opentelemetry-api/${OTEL_VERSION}/opentelemetry-api-${OTEL_VERSION}.jar"
  "io/opentelemetry/opentelemetry-context/${OTEL_VERSION}/opentelemetry-context-${OTEL_VERSION}.jar"
  "io/opentelemetry/opentelemetry-sdk-common/${OTEL_VERSION}/opentelemetry-sdk-common-${OTEL_VERSION}.jar"
  "io/opentelemetry/opentelemetry-sdk/${OTEL_VERSION}/opentelemetry-sdk-${OTEL_VERSION}.jar"
  "io/opentelemetry/opentelemetry-sdk-extension-autoconfigure-spi/${OTEL_VERSION}/opentelemetry-sdk-extension-autoconfigure-spi-${OTEL_VERSION}.jar"
  "io/opentelemetry/opentelemetry-sdk-extension-autoconfigure/${OTEL_VERSION}/opentelemetry-sdk-extension-autoconfigure-${OTEL_VERSION}.jar"
  "io/opentelemetry/javaagent/opentelemetry-javaagent-extension-api/${AGENT_EXT_API_VERSION}/opentelemetry-javaagent-extension-api-${AGENT_EXT_API_VERSION}.jar"
  "com/google/protobuf/protobuf-java/${PROTOBUF_VERSION}/protobuf-java-${PROTOBUF_VERSION}.jar"
  "io/opentelemetry/proto/opentelemetry-proto/${OTEL_PROTO_VERSION}/opentelemetry-proto-${OTEL_PROTO_VERSION}.jar"
)

BUNDLED_ARTIFACTS=(
  "io/opentelemetry/proto/opentelemetry-proto/${OTEL_PROTO_VERSION}/opentelemetry-proto-${OTEL_PROTO_VERSION}.jar"
)

fetch() {
  local path="$1"
  local jar_name
  jar_name="$(basename "${path}")"
  if [[ -f "${LIBS_DIR}/${jar_name}" ]]; then
    echo "    cached  ${jar_name}"
  else
    echo "    get     ${jar_name}"
    curl -fsSL "${MAVEN_CENTRAL}/${path}" -o "${LIBS_DIR}/${jar_name}"
  fi
}

echo "==> Fetching dependencies"
mkdir -p "${LIBS_DIR}"
for path in "${COMPILE_ONLY_ARTIFACTS[@]}"; do fetch "${path}"; done
for path in "${BUNDLED_ARTIFACTS[@]}"; do fetch "${path}"; done

CP="$(find "${LIBS_DIR}" -name '*.jar' | tr '\n' ':')"

echo "==> Compiling"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
# jdk.jfr is a JDK module; --add-modules makes it visible to javac.
javac \
  --add-modules jdk.jfr \
  -Xlint:-options \
  -classpath "${CP}" \
  -d "${BUILD_DIR}" \
  $(find src/main/java -name '*.java')

echo "==> Unpacking bundled runtime deps into the jar (proto + protobuf)"
for path in "${BUNDLED_ARTIFACTS[@]}"; do
  jar_name="$(basename "${path}")"
  # Exclude META-INF (avoid manifest/signature/service clashes) and any
  # module-info (ignored on the classpath, and confusing inside a fat jar).
  unzip -o -q "${LIBS_DIR}/${jar_name}" -d "${BUILD_DIR}" -x "META-INF/*" "module-info.class" "**/module-info.class"
done

echo "==> Packaging ${OUT_JAR} (version ${FULL_VERSION})"
MANIFEST="build/MANIFEST.MF"
cat > "${MANIFEST}" <<EOF
Manifest-Version: 1.0
Implementation-Title: ${ARTIFACT_BASE}
Implementation-Version: ${FULL_VERSION}
EOF

rm -f "${OUT_JAR}"
jar --create --file "${OUT_JAR}" --manifest "${MANIFEST}" \
  -C "${BUILD_DIR}" . \
  -C src/main/resources .

# Stable, unversioned copy so the README's -Dotel.javaagent.extensions path works.
cp -f "${OUT_JAR}" "${STABLE_JAR}"

# Only consume a build number on a fully successful build (set -e aborts earlier).
echo "${BUILD_NUMBER}" > "${BUILD_NUMBER_FILE}"

echo "==> Done: $(pwd)/${OUT_JAR}"
echo "          $(pwd)/${STABLE_JAR} (stable copy)"
echo "    $(unzip -l "${OUT_JAR}" | tail -1)"
