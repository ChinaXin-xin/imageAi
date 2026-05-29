package xin.students.imageaioriginal.service;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import xin.students.imageaioriginal.model.SystemOverview;

import java.io.File;
import java.lang.management.ManagementFactory;

@Service
public class SystemOverviewService {

    private final String appName;

    public SystemOverviewService(@Value("${spring.application.name:ImageAI}") String appName) {
        this.appName = appName;
    }

    public SystemOverview getOverview() {
        OperatingSystemMXBean osBean =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double cpuUsage = normalizePercent(osBean.getCpuLoad() * 100);
        long memoryTotal = osBean.getTotalMemorySize();
        long memoryFree = osBean.getFreeMemorySize();
        long memoryUsed = Math.max(0, memoryTotal - memoryFree);

        DiskUsage diskUsage = readDiskUsage();
        String version = System.getProperty("os.name") + " " + System.getProperty("os.version");

        return new SystemOverview(
                appName,
                resolveAppVersion(),
                version,
                cpuUsage,
                memoryTotal,
                memoryUsed,
                percent(memoryUsed, memoryTotal),
                diskUsage.totalBytes(),
                diskUsage.usedBytes(),
                percent(diskUsage.usedBytes(), diskUsage.totalBytes())
        );
    }

    private String resolveAppVersion() {
        Package appPackage = SystemOverviewService.class.getPackage();
        String version = appPackage == null ? null : appPackage.getImplementationVersion();
        return version == null || version.isBlank() ? "0.0.1-SNAPSHOT" : version;
    }

    private DiskUsage readDiskUsage() {
        long total = 0;
        long free = 0;
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                total += Math.max(0, root.getTotalSpace());
                free += Math.max(0, root.getFreeSpace());
            }
        }
        return new DiskUsage(total, Math.max(0, total - free));
    }

    private double percent(long used, long total) {
        if (total <= 0) {
            return 0;
        }
        return normalizePercent((double) used * 100 / total);
    }

    private double normalizePercent(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            return 0;
        }
        return Math.round(Math.min(100, value) * 10.0) / 10.0;
    }

    private record DiskUsage(long totalBytes, long usedBytes) {
    }
}
