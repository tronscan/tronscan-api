FROM gizmotronic/oracle-java:java8
MAINTAINER Rovak

RUN apt-get update && apt-get install unzip

ADD ./target/universal/tronscan-1.0-SNAPSHOT.zip /opt/tronscan-api/tronscan.zip
#VOLUME ~/.ivy2 /root/.ivy2 ~/.sbt:/root/.sb

RUN cd /opt/tronscan-api && unzip tronscan.zip

CMD /opt/tronscan-api/tronscan-1.0-SNAPSHOT/bin/tronscan \
    -J-Xms128M -J-Xmx8096m \
    -Dconfig.resource=docker.conf
