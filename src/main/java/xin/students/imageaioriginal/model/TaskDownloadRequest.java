package xin.students.imageaioriginal.model;

import java.util.List;

public record TaskDownloadRequest(
        List<String> taskIds
) {
}
