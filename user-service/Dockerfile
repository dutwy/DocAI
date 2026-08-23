ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${JAVA_RUNTIME_IMAGE}

RUN groupadd --system docai && \
    useradd --system --gid docai --home-dir /app --shell /usr/sbin/nologin docai

ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --chown=docai:docai target/user-service-1.0.0.jar /app/app.jar
USER docai

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
