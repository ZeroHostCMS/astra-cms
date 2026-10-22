# Astra Keycloak: upstream Keycloak with the Astra login theme and realm baked in.
# Build from the repository root: docker build -t astra/keycloak:dev deploy/keycloak
FROM quay.io/keycloak/keycloak:26.8.1 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_HTTP_RELATIVE_PATH=/auth
COPY themes/astra /opt/keycloak/themes/astra
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
# The Astra realm and the acme-corp-idp realm (demo enterprise SAML IdP brokered by the Astra realm).
COPY realm/*.json /opt/keycloak/data/import/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
