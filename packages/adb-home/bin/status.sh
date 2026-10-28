#!/system/bin/sh
. /data/nemesis/apps/adb.home/scripts/common.sh
if on_home_wifi; then echo "network: home Wi-Fi"; else echo "network: not home"; fi
echo "adbd: $(getprop init.svc.adbd)  port: $(getprop persist.adb.tcp.port)"
echo "stock switch persist.service.adb.tcp.enable=$(getprop persist.service.adb.tcp.enable)"
echo "eth0: $(ifconfig eth0 2>&1)"
