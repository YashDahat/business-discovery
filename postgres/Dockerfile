# Postgres 16 (same alpine base as before, so the existing pgdata volume stays byte-compatible) with the
# pgvector extension compiled in. Keeps data-in-container unchanged; only adds the `vector` extension files.
FROM postgres:16-alpine

ARG PGVECTOR_VERSION=v0.8.0

# Build with with_llvm=no — skips optional JIT bitcode (.bc), so we don't need a clang/llvm matching the
# image's LLVM 21. The vector extension .so still works; only JIT inlining of pgvector funcs is skipped.
RUN set -eux; \
    apk add --no-cache --virtual .build-deps build-base git; \
    git clone --branch "${PGVECTOR_VERSION}" --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector; \
    cd /tmp/pgvector; \
    make with_llvm=no; \
    make with_llvm=no install; \
    cd /; \
    rm -rf /tmp/pgvector; \
    apk del .build-deps
