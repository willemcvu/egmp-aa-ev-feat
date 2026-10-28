#!/system/bin/sh
. /data/nemesis/apps/adb.home/scripts/common.sh

AWAY_LIMIT=3        # consecutive away checks before stopping adbd (rides out Wi-Fi blips)
away=0
last=""

log "adbd-home started (home gateway $HOME_GW_MAC)"
while true; do
    eth0_tidy
    if on_home_wifi; then
        away=0
        [ "$(getprop persist.adb.tcp.port)" = 5555 ] || setprop persist.adb.tcp.port 5555
        if [ "$(getprop init.svc.adbd)" != running ]; then
            start adbd
            log "home Wi-Fi: started adbd"
        fi
        [ "$last" = home ] || log "state: home"
        last=home
    else
        away=$((away + 1))
        if [ "$away" -ge "$AWAY_LIMIT" ]; then
            if [ "$(getprop init.svc.adbd)" = running ]; then
                stop adbd
                log "not on home Wi-Fi: stopped adbd"
            fi
            [ "$last" = away ] || log "state: away"
            last=away
        fi
    fi
    sleep 10
done
