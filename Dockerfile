FROM hseeberger/scala-sbt
MAINTAINER Rovak

ADD . /opt/tronscan-api
#VOLUME ~/.ivy2 /root/.ivy2 ~/.sbt:/root/.sb

RUN cd /opt/tronscan-api && sbt stage

CMD /opt/tronscan-api/target/universal/stage/bin/tron-explorer \
    -J-Xms128M -J-Xmx8096m \
    -Dplay.http.secret.key=lkjasdjlksafdkjlfsadlkjafdsjksafd \
    -Dconfig.resource=local.conf