FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r jesoos && useradd -r -g jesoos jesoos
RUN mkdir -p /app/segmented /app/merged /app/controller-uploads /app/external /app/file-uploads /var/log/jesoos \
    && chown -R jesoos:jesoos /app /var/log/jesoos
WORKDIR /app
COPY third_party/ffmpeg/linux_x86_64/ffmpeg /usr/bin/ffmpeg
COPY third_party/ffmpeg/linux_x86_64/ffprobe /usr/bin/ffprobe
RUN chmod +x /usr/bin/ffmpeg /usr/bin/ffprobe
COPY target/jesoos-*-runner.jar app.jar
RUN chown jesoos:jesoos app.jar
USER jesoos
EXPOSE 8080 38797
ENTRYPOINT ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "-jar", "app.jar"]