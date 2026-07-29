FROM flink:1.20.0-java11
RUN mkdir -p /opt/flink/usrlib
COPY target/Skyline-Project-Flink-1.0-SNAPSHOT.jar /opt/flink/usrlib/Skyline-Project-Flink-1.0-SNAPSHOT.jar
COPY deploy/config.properties /opt/flink/usrlib/config.properties
