#!/system/bin/sh
# adb.home: run adbd (TCP 5555) only while wlan0 is on the home network, and keep the
# stock ADB-over-network switch off so eth0 stays down (the switch gives eth0
# 192.168.0.123, which clashes with the wireless projection hotspot).

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

# Home router (gateway) MAC on wlan0. Set this to your router's MAC and rebuild/reinstall.
HOME_GW_MAC=00:00:00:00:00:00
STATE=/data/nemesis/adb.home.state
LOG=/data/nemesis/adb.home.log

log() {
    echo "$1"
    echo "$(date) $1" >> "$LOG" 2>/dev/null
}

# 0 when the home router answers ARP on wlan0 (complete entry, flags 0x2).
on_home_wifi() {
    grep -q "0x2 *$HOME_GW_MAC .*wlan0" /proc/net/arp
}

# Undo the stock switch's side effect: eth0 back to down with no address (stock state).
# NEMESIS root has no CAP_NET_ADMIN, so ifconfig fails; netd (via ndc) does it for us.
eth0_tidy() {
    [ "$(getprop persist.service.adb.tcp.enable)" = 1 ] && return 0
    case "$(ndc interface getcfg eth0 2>/dev/null)" in
        *192.168.0.123*) ;;
        *) return 0 ;;
    esac
    ndc interface setcfg eth0 0.0.0.0 0 down >/dev/null 2>&1
    case "$(ndc interface getcfg eth0 2>/dev/null)" in
        *192.168.0.123*)
            [ -n "$ETH0_FAILED" ] || log "eth0: could not remove 192.168.0.123 (ndc setcfg failed)"
            ETH0_FAILED=1
            ;;
        *)
            log "eth0: removed 192.168.0.123 and set it down"
            ETH0_FAILED=""
            ;;
    esac
}
