package xin.students.imageaioriginal.model;

public record CodexQuotaAccount(
        String id,
        String name,
        String fileName,
        String status,
        String statusText,
        String planType,
        QuotaWindowView fiveHour,
        QuotaWindowView weekly,
        Integer fiveHourImages,
        Integer weeklyImages,
        String lastRefreshTime,
        String error
) {
}
