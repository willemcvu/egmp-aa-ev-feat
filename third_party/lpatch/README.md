# lpatch

From [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus) (`tools/lpatch/`, `tools/mkdelta.py`, commit `08f4436`), GPL-3.0 (`LICENSE`). Copied unmodified.

- `lpatch`: the static ARM binary that runs on the head unit (md5 `be4a186a1c92d0d82e50040a496b753f`, the same file Leafia v1.0.5 ships). `build.sh` rebuilds it from `lpatch.c`.
- `mkdelta.py`: makes the `.lpd` deltas on the dev machine (needs `bsdiff4`).

The aa.ev.model package uses `lpatch delta` to rebuild its GPM APK and AA library on the car from the car's own Leafia files, so the release carries no Hyundai or Google binaries.
