# RK3588 Deploy Note (llamacpp server with rk-llama.cpp)

### Set ulimit page
```bash
ulimit -n 65536
```

### Set swap page using zram

### Set cpu, gpu and 
```bash
echo performance | tee /sys/devices/system/cpu/cpufreq/policy*/scaling_governor
echo performance | tee /sys/class/devfreq/ff9a0000.gpu/governor
echo performance | tee /sys/class/devfreq/ff9a0000.gpu/available_governors
```

### Run service on taskset core 4-7 (big core)
```bash
taskset -c 4-7 <your command>
```
