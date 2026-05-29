package xin.students.imageaioriginal.model;

public record QuotaWindowView(
        Integer remainingPercent,
        Integer usedPercent,
        String resetLabel
) {
}
