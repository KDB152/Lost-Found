#!/bin/sh
# LostFound (CORBA) sous Linux / macOS - necessite un JDK 8 (idlj, orbd)
cd "$(dirname "$0")"
J="$(dirname "$(command -v idlj)")"   # java/javac du meme JDK 8 que idlj
[ -x "$J/idlj" ] || { echo "JDK 8 requis (idlj, orbd)"; exit 1; }
if [ ! -f out/lostfound/web/WebGateway.class ]; then
  rm -rf gen out && mkdir -p gen out
  "$J"/idlj -fall -td gen idl/LostFound.idl || exit 1
  "$J"/javac -nowarn -encoding UTF-8 -d out $(find gen src -name '*.java') || exit 1
fi
ORB="-ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 -ORBServerHost 127.0.0.1"
"$J"/orbd -ORBInitialPort 1050 -ORBServerHost 127.0.0.1 & NS=$!
sleep 3
"$J"/java -cp out lostfound.server.ServerMain $ORB & SRV=$!
sleep 3
"$J"/java -cp out lostfound.web.WebGateway $ORB & GW=$!
trap 'kill $GW $SRV $NS 2>/dev/null' INT TERM EXIT
echo "Ouvrez http://localhost:4173  (Ctrl+C pour arreter)"
wait
