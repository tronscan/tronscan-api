FROM gizmotronic/oracle-java:java8
MAINTAINER Rovak

ADD ./target/universal/stage /opt/tronscan-api

CMD /opt/tronscan-api/bin/tronscan \
    -J-Xms128M -J-Xmx2048m \
    -Dpidfile.path=/dev/null \
    -Dconfig.resource=docker.conf
