FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r djinn && useradd -r -g djinn djinn
RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /app/segmented /app/merged /app/controller-uploads /app/external /app/file-uploads /var/log/djinn \
    && chown -R djinn:djinn /app /var/log/djinn
WORKDIR /app
COPY target/djinn-*-runner.jar app.jar
RUN chown djinn:djinn app.jar
USER djinn
EXPOSE 8080 38708
ENTRYPOINT ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "-jar", "app.jar"]
