#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TLS_DIR="$SCRIPT_DIR/tls"
STORE_PASSWORD="${COURIER_TEST_STORE_PASSWORD:-changeit}"
VALIDITY="${COURIER_TEST_CERT_DAYS:-7}"

mkdir -p "$TLS_DIR"
# Keep the directory's ignore rule while removing only generated certificate
# material, so a test run cannot make private keys appear as deliverables.
find "$TLS_DIR" -mindepth 1 ! -name .gitignore -exec rm -rf {} +

keytool -genkeypair -noprompt -alias ca -keyalg RSA -keysize 3072 -validity "$VALIDITY" \
  -dname "CN=YunQi Courier Test CA" -ext bc=ca:true -ext ku=keyCertSign,cRLSign \
  -keystore "$TLS_DIR/ca.p12" -storetype PKCS12 -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD"
keytool -exportcert -rfc -alias ca -keystore "$TLS_DIR/ca.p12" \
  -storepass "$STORE_PASSWORD" -file "$TLS_DIR/ca.crt"

keytool -genkeypair -noprompt -alias zookeeper -keyalg RSA -keysize 2048 -validity "$VALIDITY" \
  -dname "CN=zookeeper" -ext "SAN=dns:zookeeper,dns:localhost,ip:127.0.0.1" \
  -keystore "$TLS_DIR/server.p12" -storetype PKCS12 -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD"
keytool -certreq -alias zookeeper -keystore "$TLS_DIR/server.p12" -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD" -file "$TLS_DIR/server.csr"
keytool -gencert -noprompt -alias ca -keystore "$TLS_DIR/ca.p12" -storepass "$STORE_PASSWORD" \
  -infile "$TLS_DIR/server.csr" -outfile "$TLS_DIR/server.crt" -rfc -validity "$VALIDITY" \
  -ext "SAN=dns:zookeeper,dns:localhost,ip:127.0.0.1" -ext "KU=digitalSignature,keyEncipherment" \
  -ext "EKU=serverAuth"
keytool -importcert -noprompt -alias ca -file "$TLS_DIR/ca.crt" -keystore "$TLS_DIR/server.p12" \
  -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD"
keytool -importcert -noprompt -alias zookeeper -file "$TLS_DIR/server.crt" -keystore "$TLS_DIR/server.p12" \
  -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD"

keytool -genkeypair -noprompt -alias courier-client -keyalg RSA -keysize 2048 -validity "$VALIDITY" \
  -dname "CN=courier-client" -ext "SAN=dns:courier-client" \
  -keystore "$TLS_DIR/client.p12" -storetype PKCS12 -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD"
keytool -certreq -alias courier-client -keystore "$TLS_DIR/client.p12" -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD" -file "$TLS_DIR/client.csr"
keytool -gencert -noprompt -alias ca -keystore "$TLS_DIR/ca.p12" -storepass "$STORE_PASSWORD" \
  -infile "$TLS_DIR/client.csr" -outfile "$TLS_DIR/client.crt" -rfc -validity "$VALIDITY" \
  -ext "KU=digitalSignature,keyEncipherment" -ext "EKU=clientAuth"
keytool -importcert -noprompt -alias ca -file "$TLS_DIR/ca.crt" -keystore "$TLS_DIR/client.p12" \
  -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD"
keytool -importcert -noprompt -alias courier-client -file "$TLS_DIR/client.crt" -keystore "$TLS_DIR/client.p12" \
  -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD"

keytool -importcert -noprompt -alias ca -file "$TLS_DIR/ca.crt" -keystore "$TLS_DIR/server-trust.p12" \
  -storetype PKCS12 -storepass "$STORE_PASSWORD"
keytool -importcert -noprompt -alias ca -file "$TLS_DIR/ca.crt" -keystore "$TLS_DIR/client-trust.p12" \
  -storetype PKCS12 -storepass "$STORE_PASSWORD"

rm -f "$TLS_DIR"/*.csr "$TLS_DIR"/*.crt
chmod 600 "$TLS_DIR"/*.p12
printf 'Generated test keystores under %s\n' "$TLS_DIR"
