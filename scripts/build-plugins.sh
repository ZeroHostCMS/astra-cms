#!/usr/bin/env bash
# Builds the Express demo's Tier 1 plugins (carrier and e-invoice) and copies each jar to
# astra-apps/express-integrations/plugins/<id>-<version>.jar, where scripts/demo-tiers.sh uploads them from.
set -euo pipefail
cd "$(dirname "$0")/.."
if JDK25=$(/usr/libexec/java_home -v 25 2>/dev/null); then export JAVA_HOME="$JDK25"; fi
./mvnw -q -pl astra-spi install -DskipTests
./mvnw -q -f astra-apps/express-integrations/pom.xml package -DskipTests
out=astra-apps/express-integrations/plugins
mkdir -p "$out"
for m in fastship-1.0.0 fastship-1.1.0 fastship-1.2.0-bad einvoice-vn einvoice-kh; do
  dir=astra-apps/express-integrations/$m
  id=$(sed -n 's/^id: *//p' "$dir/src/main/resources/META-INF/astra-app.yaml")
  version=$(sed -n 's/^version: *//p' "$dir/src/main/resources/META-INF/astra-app.yaml")
  cp "$dir/target/plugin-$m.jar" "$out/$id-$version.jar"
  printf '%-24s %6d bytes\n' "$id-$version.jar" "$(wc -c < "$out/$id-$version.jar")"
done
