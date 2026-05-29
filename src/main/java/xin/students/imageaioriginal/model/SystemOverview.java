package xin.students.imageaioriginal.model;

public record SystemOverview(
        String appName,
        String appVersion,
        String osFamily,
        String systemVersion,
        double cpuUsagePercent,
        long memoryTotalBytes,
        long memoryUsedBytes,
        double memoryUsagePercent,
        long diskTotalBytes,
        long diskUsedBytes,
        double diskUsagePercent
) {
}
