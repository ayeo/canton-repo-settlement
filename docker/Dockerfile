# Canton image built from the release jar: the public canton-open-source images
# stop at 2.8.0-rc1, and Digital Asset's JFrog registry requires an account.
FROM eclipse-temurin:21-jre

RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin canton

WORKDIR /canton
COPY canton.jar /canton/canton.jar
RUN mkdir -p /canton/log && chown -R canton:canton /canton

USER canton

# Memory limits come from compose through JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-jar", "/canton/canton.jar"]
CMD ["--help"]
