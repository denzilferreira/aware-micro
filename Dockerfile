FROM openjdk:8

COPY . /aware-micro
WORKDIR /aware-micro
ENTRYPOINT ["/aware-micro/gradlew"]
