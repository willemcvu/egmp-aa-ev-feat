# v0.2.1

For the 2022 Ioniq 5 (US), Gen5W software 260507, with NEMESIS and **HMG Leafia Plus v1.0.5 installed first**. Changes since v0.1.1.

## New

- **Real usable capacity**, learned from the BMS instead of the new-car 77.4 kWh.
- **Cold-weather model** driven by the live outside temperature.
- **Realistic charge times**, including the slow last 10–20 %. (Earlier versions sent the charge tables in a form Maps misread, so 100 % looked much faster than it is.)
- **Automatic battery preconditioning** when the next stop is a charger over 50 kW within an hour. Turn it off with the car's *battery conditioning* setting.
- **EV logs** whenever the car is on, driving or charging: battery energy and power, speed, temperatures, climate, charging, Maps' route plan. Copied to any USB drive with an `egmp-aa-ev-logs` folder, with a pop-up when done. Share them through an [EV log issue](https://github.com/willemcvu/egmp-aa-ev-feat/issues/new?template=log-submission.yml).

## Install

Copy the zip's contents to an empty FAT32/exFAT drive, plug it in with the car on, and tap **Yes**. It upgrades earlier versions in place and restarts the head unit; the first Android Auto start afterwards can take an extra minute.

## Known issues

- The drive-mode column in the logs stays empty.
- The BMS reports ~62.9 kWh usable on the test car, while recent fast-charge sessions suggest ~70 kWh. A logged charge will settle it.
- Only this car and software version; the installer refuses anything else.
