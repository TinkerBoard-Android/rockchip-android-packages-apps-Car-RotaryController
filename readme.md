# Car rotary service

## Building
```
make CarRotaryController -j64
```

## Enable/disable RotaryService
To enable, run:
```
adb shell settings put secure enabled_accessibility_services com.android.car.rotary/com.android.car.rotary.RotaryService
```
To disable, run:
```
adb shell settings delete secure enabled_accessibility_services
```
