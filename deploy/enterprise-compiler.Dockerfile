FROM public.ecr.aws/feldera/compiler-server:0.391.0@sha256:df97e718f2544d95b120b2ab87250abb16e097f46f27d20be00587ce2f9fde79

# build-enterprise-compiler.sh puts only the reviewed runtime sources in crates/.
COPY --chown=ubuntu crates/ feldera/crates/
COPY --chown=ubuntu compiler.jar feldera/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar

COPY --chown=ubuntu Cargo.toml Cargo.lock feldera/

RUN tar -xzf dot-cargo.tar.gz -C / \
    && rm working-dir-precompiled.tar.gz \
    && ./compiler-server --compiler-working-directory="$HOME/working-dir" \
        --sql-compiler-path="$HOME/feldera/sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar" \
        --compilation-cargo-lock-path="$HOME/feldera/Cargo.lock" \
        --dbsp-override-path="$HOME/feldera" --precompile \
    && stamp=$(date +%s) && find "$HOME/working-dir" -exec touch -h -d "@$stamp" {} + \
    && tar -czf working-dir-precompiled.tar.gz -C / home/ubuntu/working-dir \
    && tar -czf dot-cargo.tar.gz -C / home/ubuntu/.cargo \
    && rm -rf "$HOME/working-dir" "$HOME/.cargo"

ENV FELDERA_SPARK_COMPAT=true

ARG REVISION
LABEL org.opencontainers.image.revision="$REVISION"
