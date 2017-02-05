FROM openjdk:8-jre
WORKDIR /opt/docker
ADD https://github.com/akka/akka/raw/v2.4.16/akka-kernel/src/main/dist/bin/jmxsh-R5.jar bin/
ADD https://raw.githubusercontent.com/akka/akka/v2.4.16/akka-kernel/src/main/dist/bin/akka-cluster bin/
RUN chmod +x bin/akka-cluster
ENTRYPOINT ["bin/akka-cluster"]
CMD []
