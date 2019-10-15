FROM openjdk:8
LABEL maintainer="joaquin@nurelm.com"

COPY . /aware-micro
WORKDIR /aware-micro
RUN /aware-micro/generate_aware_config.sh
ENTRYPOINT ["/aware-micro/gradlew"]
CMD ["clean", "build", "run"]
