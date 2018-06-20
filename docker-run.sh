docker build -t tronscan .
docker run \
  -v ~/.ivy2:/root/.ivy2 \
  -v ~/.sbt:/root/.sbt \
  -it tronscan