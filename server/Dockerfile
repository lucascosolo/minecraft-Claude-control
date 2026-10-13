FROM eclipse-temurin:25-jre-noble@sha256:78498f30dd330b06755c1b134039dcbf8324bedb385136819dd519b842a3816a
WORKDIR /server
# Refuse an older/unverified jar before Minecraft can open an upgraded world.
CMD ["sh", "-c", "if [ \"$(sha256sum /server/server.jar)\" != 'cada5af7f14363bc8545c0e973130de9431f6dc678dc612759187695027c27d9  /server/server.jar' ]; then echo 'Refusing server jar mismatch. Run tools/start_26_3.py; never downgrade an upgraded world.' >&2; exit 1; fi; exec java -Djna.tmpdir=/server/.jna-tmp -Xms1G -Xmx4G -jar /server/server.jar nogui"]
