# Private derivative; use deploy/build-enterprise-compiler.sh to prepare the
# pinned 0.389 source context, not the older onelake checkout.
FROM public.ecr.aws/feldera/compiler-server:0.389.0@sha256:953a1f19066530ff43407a081a3985f85d8a1f3938d3450cc6dedeab71fb6e0c

USER ubuntu
WORKDIR /home/ubuntu

COPY Cargo.lock /tmp/branch-Cargo.lock
RUN cmp /tmp/branch-Cargo.lock feldera/Cargo.lock

COPY --chown=ubuntu crates/connectors/src/integrated/ feldera/crates/connectors/src/integrated/
COPY --chown=ubuntu crates/feldera-types/src/transport/ feldera/crates/feldera-types/src/transport/
COPY --chown=ubuntu build/sql2dbsp-jar-with-dependencies.jar feldera/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar

# Whole-second tar mtimes must not make a dependency newer than its dependent,
# which would invalidate the precompile cache on the first program compilation.
RUN tar -xzf dot-cargo.tar.gz -C / \
    && rm working-dir-precompiled.tar.gz \
    && ./compiler-server --compiler-working-directory=$HOME/working-dir --sql-compiler-path=$HOME/feldera/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar --compilation-cargo-lock-path=$HOME/feldera/Cargo.lock --dbsp-override-path=$HOME/feldera --precompile \
    && built=$(date +%s) && find $HOME/working-dir -exec touch -h -d "@$built" {} + \
    && tar czf $HOME/working-dir-precompiled.tar.gz $HOME/working-dir \
    && rm -rf $HOME/working-dir $HOME/.cargo

ARG REVISION
ARG PUBLIC_SOURCE_REVISION
RUN test -n "$REVISION" && test -n "$PUBLIC_SOURCE_REVISION"
LABEL org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="https://github.com/mdrakiburrahman/feldera" \
      org.opencontainers.image.base.name="public.ecr.aws/feldera/compiler-server:0.389.0" \
      io.feldera.compiler.public-source-revision="${PUBLIC_SOURCE_REVISION}"
