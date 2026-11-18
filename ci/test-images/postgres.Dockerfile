# CI-only derivative; upstream OS packages are unchanged. Trivy gates the full final image.
ARG BASE=postgres@sha256:f377d590e5474681d4d7838635031a2b50647923b7abfa803ce9b5ff35d5c663
FROM ${BASE}
ARG ORIGINAL_USER=0
USER 0
COPY --chmod=0755 gosu /usr/local/bin/gosu
USER ${ORIGINAL_USER}
