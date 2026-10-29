# Per-car build inputs (Leafia v1.0.5 files in private/). Car ids follow Leafia's
# patches/cars. Both cars share Leafia's library; the GPM differs.

# car_inputs <car>: set LEAFIA_DIR and STOCK_APK for that car
car_inputs() {
    case "$1" in
        neev)
            LEAFIA_DIR=private/car-current-leafia105
            STOCK_APK=private/car-neev-260507/LEAFIA/private/stock/app/HKMC_GPM.apk ;;
        ev6)
            LEAFIA_DIR=private/ev6/leafia105
            STOCK_APK=private/ev6/stock/HKMC_GPM.apk ;;
        *)
            echo "unknown car: $1" >&2
            return 1 ;;
    esac
}

# cars_present: the cars whose inputs exist
cars_present() {
    local _c _out=""
    for _c in neev ev6; do
        car_inputs "$_c"
        [ -s "$LEAFIA_DIR/HKMC_GPM.apk" ] && [ -s "$STOCK_APK" ] && _out="$_out $_c"
    done
    echo $_out
}
