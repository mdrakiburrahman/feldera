# Private derivative of the licensed Feldera Enterprise compiler-server image.
# It keeps the vendor compiler, entrypoint and Enterprise crates, overlays this
# branch's Delta connector sources and SQL compiler jar, and repeats the vendor
# precompile so compiled pipelines include them. Push only to a private
# registry. See "Enterprise compiler derivative" in deploy/README.md.
ARG BASE_IMAGE=public.ecr.aws/feldera/compiler-server:0.389.0@sha256:953a1f19066530ff43407a081a3985f85d8a1f3938d3450cc6dedeab71fb6e0c
FROM ${BASE_IMAGE}
ARG REVISION=unknown
LABEL org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="https://github.com/mdrakiburrahman/feldera" \
      org.opencontainers.image.base.name="public.ecr.aws/feldera/compiler-server:0.389.0"

USER ubuntu
WORKDIR /home/ubuntu

# The overlay is only valid on the release these sources are based on.
COPY Cargo.lock /tmp/branch-Cargo.lock
RUN cmp /tmp/branch-Cargo.lock feldera/Cargo.lock

COPY --chown=ubuntu crates/connectors/src/integrated/ feldera/crates/connectors/src/integrated/
COPY --chown=ubuntu crates/feldera-types/src/transport/ feldera/crates/feldera-types/src/transport/
COPY --chown=ubuntu build/sql2dbsp-jar-with-dependencies.jar feldera/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar

# The tar keeps whole-second mtimes. A build that crosses a second boundary
# can leave a dependency stamped after its dependent, and Cargo would then
# rebuild most of the graph on each pod's first compile, so give every
# precompiled file the same mtime.
RUN tar -xzf dot-cargo.tar.gz -C / \
    && rm working-dir-precompiled.tar.gz \
    && ./compiler-server --compiler-working-directory=$HOME/working-dir --sql-compiler-path=$HOME/feldera/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar --compilation-cargo-lock-path=$HOME/feldera/Cargo.lock --dbsp-override-path=$HOME/feldera --precompile \
    && built=$(date +%s) && find $HOME/working-dir -exec touch -h -d "@$built" {} + \
    && tar czf $HOME/working-dir-precompiled.tar.gz $HOME/working-dir \
    && rm -rf $HOME/working-dir $HOME/.cargo
