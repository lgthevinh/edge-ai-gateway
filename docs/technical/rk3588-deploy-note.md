# RK3588 Deploy Note

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

### Build llama.cpp and put to location

#### Library Location
This project has to load a single shared library jllama.

Note, that the file name varies between operating systems, e.g., jllama.dll on Windows, jllama.so on Linux, and jllama.dylib on macOS.

The application will search in the following order in the following locations:
- In de.kherud.llama.lib.path: Use this option if you want a custom location for your shared libraries, i.e., set VM option -Dde.kherud.llama.lib.path=/path/to/directory.
- In java.library.path: These are predefined locations for each OS, e.g., /usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib on Linux. You can find out the locations using System.out.println(System.getProperty("java.library.path")). Use this option if you want to install the shared libraries as system libraries.
- From the JAR: If any of the libraries weren't found yet, the application will try to use a prebuilt shared library. This of course only works for the supported platforms .
