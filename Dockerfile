FROM gizmotronic/oracle-java:java8
MAINTAINER Rovak

RUN apt-get update && apt-get install unzip

ADD ./target/universal/tronscan-latest.zip /opt/tronscan-api/tronscan.zip

RUN cd /opt/tronscan-api && unzip tronscan.zip

CMD /opt/tronscan-api/tronscan-latest/bin/tronscan \
    -J-Xms128M -J-Xmx2048m \
    -Dconfig.resource=docker.conf
