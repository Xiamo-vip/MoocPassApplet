package top.xiamoi.moocpass.task;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.platform.CourseResource;
import java.time.*;
import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskSettings(
    List<String> resourceIds,
    List<String> resourceTypes,
    double speed,
    boolean autoAnswer,
    String answerMode,
    boolean aiBestEffort,
    @JsonAlias({"profileId", "answerProfileId"}) Long answerProfileId,
    Long answerVersionId,
    Long fallbackProfileId,
    Long fallbackVersionId,
    double coverRate,
    int maxRetries,
    String startTime,
    String endTime
) {
    public TaskSettings {
        if (resourceIds == null) resourceIds = List.of();
        if (resourceTypes == null || resourceTypes.isEmpty()) {
            resourceTypes = List.of("video", "audio", "document", "read");
        }
        if (answerMode == null || "PREVIEW".equals(answerMode)) answerMode = "SAVE";
        if (startTime == null || startTime.isBlank()) startTime = "00:00";
        if (endTime == null || endTime.isBlank()) endTime = "00:00";
    }

    public static TaskSettings defaults() {
        return new TaskSettings(List.of(), List.of("video", "audio", "document", "read"), 1.0,
            false, "SAVE", false, null, null, null, null, 1.0, 2, "00:00", "00:00");
    }
    public void validate() {
        if (resourceIds == null || resourceTypes == null || resourceIds.size() > 500 || resourceTypes.isEmpty()
            || !Set.of("video","audio","document","read","workid").containsAll(resourceTypes))
            throw ApiException.invalid("请选择有效的资源范围");
        if (!Double.isFinite(speed) || speed < 1 || speed > 2) throw ApiException.invalid("播放速度范围为 1–2");
        if (!Set.of("SAVE","SUBMIT").contains(answerMode)) throw ApiException.invalid("答案处理模式无效");
        if (!Double.isFinite(coverRate) || coverRate < 0.8 || coverRate > 1 || maxRetries < 0 || maxRetries > 3)
            throw ApiException.invalid("覆盖率或重试次数无效");
        try { LocalTime.parse(startTime); LocalTime.parse(endTime); }
        catch (Exception error) { throw ApiException.invalid("学习时间段格式应为 HH:mm"); }
        if (autoAnswer && answerProfileId == null) throw ApiException.invalid("请选择答题配置");
        if (!autoAnswer && resourceTypes.contains("workid")) throw ApiException.invalid("处理练习前请启用答题");
        if (Objects.equals(answerProfileId, fallbackProfileId) && fallbackProfileId != null)
            throw ApiException.invalid("备用配置需与首选配置不同");
    }
    public boolean includes(CourseResource resource) {
        return resourceTypes.contains(resource.type())
            && (resourceIds.isEmpty() || resourceIds.contains(resource.id()) || resourceIds.contains(resource.parentId()));
    }
    public boolean includes(String id, String parentId, String type) {
        return includes(new CourseResource(id, parentId, "", type, false));
    }
    public boolean allowedNow(ZoneId zone) {
        LocalTime start = LocalTime.parse(startTime), end = LocalTime.parse(endTime), now = LocalTime.now(zone);
        if (start.equals(end)) return true;
        return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end) : !now.isBefore(start) || now.isBefore(end);
    }
    public LocalDateTime nextWindow(ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.toLocalDate().atTime(LocalTime.parse(startTime)).atZone(zone);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return next.toLocalDateTime();
    }
    public TaskSettings versions(Long primaryVersion, Long fallbackVersion) {
        return new TaskSettings(resourceIds,resourceTypes,speed,autoAnswer,answerMode,aiBestEffort,answerProfileId,
            primaryVersion,fallbackProfileId,fallbackVersion,coverRate,maxRetries,startTime,endTime);
    }
}
