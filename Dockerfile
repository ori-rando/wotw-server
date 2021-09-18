FROM gradle:7-jdk16 as build-jar

WORKDIR /app
COPY . /app

RUN gradle jar


FROM openjdk:16-alpine

WORKDIR /app

VOLUME /app/seeds

ENV SEEDGEN_PATH=/app/seedgen/seedgen
ENV WOTW_DB_HOST=db
ENV SEED_DIR=/app/seedgen/seeds
ENV WOTW_DB=postgres
ENV WOTW_DB_PORT=5432
ENV WOTW_DB_USER=postgres

COPY --from=build-jar /app/build/libs/wotw-server.jar /app/server/wotw-server.jar
COPY --from=ghcr.io/sparkle-preference/oriwotwrandomizerclient:seedgen /app/ /app/seedgen/
COPY ./entrypoint /app/entrypoint

RUN adduser -DHu 1010 wotw && \
    chown -R wotw /app

USER wotw

ENTRYPOINT ["/app/entrypoint"]
CMD ["java", "-jar", "/app/server/wotw-server.jar"]
