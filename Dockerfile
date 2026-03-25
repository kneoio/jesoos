FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y wget apt-transport-https gnupg && \
    wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && apt-get install -y temurin-21-jre ffmpeg && \
    rm -rf /var/lib/apt/lists/*
RUN groupadd -r jesoos && useradd -r -g jesoos jesoos
RUN mkdir -p /app/segmented /app/merged /app/controller-uploads /app/external /app/file-uploads /var/log/jesoos \
    && chown -R jesoos:jesoos /app /var/log/jesoos
WORKDIR /app
COPY target/jesoos-*-runner.jar app.jar
RUN chown jesoos:jesoos app.jar
USER jesoos
EXPOSE 8080 38797
ENTRYPOINT ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "-jar", "app.jar"]