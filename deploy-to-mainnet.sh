WEB_DIR=/data/tron-explorer
sbt stage || exit 1
rsync -va target/universal/stage/ java-tron@18.216.126.238:$WEB_DIR/production
ssh java-tron@18.216.126.238 "cd $WEB_DIR && ./stop.sh && ./run.sh"