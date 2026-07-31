package io.agentscope.dataagent.conversation.application;

import io.agentscope.dataagent.conversation.infrastructure.UsageEventEntity;
import io.agentscope.dataagent.conversation.infrastructure.UsageEventRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent usage ledger. A row represents one completed chat request and contains the sum of all
 * model calls made during that request, including prompt-cache usage reported by AgentScope 2.0.
 */
@Service
public class UsageStore {

    private final UsageEventRepository repository;

    public UsageStore(UsageEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(UsageEvent event) {
        repository.save(
                new UsageEventEntity(
                        event.tenantId(),
                        event.userId(),
                        event.agentId(),
                        event.sessionKey(),
                        event.modelId(),
                        nonNegative(event.inputTokens()),
                        nonNegative(event.outputTokens()),
                        nonNegative(event.cachedPromptTokens()),
                        nonNegative(event.durationMs()),
                        nonNegative(event.costMicrousd()),
                        event.outcome(),
                        event.recordedAtMs()));
    }

    public UsageSummary summaryForUser(String userId) {
        return summary(repository.aggregateForUser(userId), repository.countForUserSince(userId, startOfToday()));
    }

    public UsageSummary summary() {
        return summary(repository.aggregateAll(), repository.countSince(startOfToday()));
    }

    public List<BucketCount> hourlyTurnsForUser(String userId, int hours) {
        return hourly(repository.findByUserIdAndRecordedAtMsGreaterThanEqual(userId, windowStartHours(hours)), hours);
    }

    public List<BucketCount> hourlyTurns(int hours) {
        return hourly(repository.findByRecordedAtMsGreaterThanEqual(windowStartHours(hours)), hours);
    }

    public List<BucketCount> dailyTurnsForUser(String userId, int days) {
        return daily(repository.findByUserIdAndRecordedAtMsGreaterThanEqual(userId, windowStartDays(days)), days);
    }

    public List<BucketCount> dailyTurns(int days) {
        return daily(repository.findByRecordedAtMsGreaterThanEqual(windowStartDays(days)), days);
    }

    public List<GroupCount> topUsersByTurns(int days, int topN) {
        return topBy(
                repository.findByRecordedAtMsGreaterThanEqual(windowStartDays(days)),
                UsageEventEntity::getUserId,
                topN);
    }

    public List<GroupCount> topAgentsByTurns(int days, int topN) {
        return topBy(
                repository.findByRecordedAtMsGreaterThanEqual(windowStartDays(days)),
                UsageEventEntity::getAgentId,
                topN);
    }

    public List<ModelUsage> modelUsageForUser(String userId, int days) {
        return modelUsage(repository.findByUserIdAndRecordedAtMsGreaterThanEqual(userId, windowStartDays(days)));
    }

    public List<ModelUsage> modelUsage(int days) {
        return modelUsage(repository.findByRecordedAtMsGreaterThanEqual(windowStartDays(days)));
    }

    private static UsageSummary summary(Object[] totals, long todayTurns) {
        Object[] values = unwrapAggregateRow(totals);
        long totalTurns = number(values, 0);
        long inputTokens = number(values, 1);
        long outputTokens = number(values, 2);
        long cachedPromptTokens = number(values, 3);
        long costMicrousd = number(values, 4);
        long avgDurationMs = number(values, 5);
        long uniqueUsers = number(values, 6);
        return new UsageSummary(
                totalTurns,
                todayTurns,
                inputTokens,
                outputTokens,
                cachedPromptTokens,
                inputTokens + outputTokens,
                costMicrousd,
                avgDurationMs,
                uniqueUsers);
    }

    private static List<BucketCount> hourly(List<UsageEventEntity> events, int requestedHours) {
        int hours = clamp(requestedHours, 1, 168);
        long start = windowStartHours(hours);
        Map<Long, Long> buckets = new TreeMap<>();
        for (int i = 0; i < hours; i++) {
            long bucket = truncateHour(start + i * 3_600_000L);
            buckets.put(bucket, 0L);
        }
        for (UsageEventEntity event : events) {
            buckets.merge(truncateHour(event.getRecordedAtMs()), 1L, Long::sum);
        }
        return buckets.entrySet().stream()
                .map(entry -> new BucketCount(entry.getKey(), labelHour(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static List<BucketCount> daily(List<UsageEventEntity> events, int requestedDays) {
        int days = clamp(requestedDays, 1, 90);
        long start = windowStartDays(days);
        Map<Long, Long> buckets = new TreeMap<>();
        for (int i = 0; i < days; i++) {
            long bucket = truncateDay(start + i * 86_400_000L);
            buckets.put(bucket, 0L);
        }
        for (UsageEventEntity event : events) {
            buckets.merge(truncateDay(event.getRecordedAtMs()), 1L, Long::sum);
        }
        return buckets.entrySet().stream()
                .map(entry -> new BucketCount(entry.getKey(), labelDay(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static List<GroupCount> topBy(
            List<UsageEventEntity> events,
            java.util.function.Function<UsageEventEntity, String> key,
            int topN) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (UsageEventEntity event : events) {
            String value = key.apply(event);
            if (value != null && !value.isBlank()) counts.merge(value, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(clamp(topN, 1, 100))
                .map(entry -> new GroupCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<ModelUsage> modelUsage(List<UsageEventEntity> events) {
        Map<String, ModelAccumulator> byModel = new LinkedHashMap<>();
        for (UsageEventEntity event : events) {
            byModel.computeIfAbsent(event.getModelId(), ignored -> new ModelAccumulator()).add(event);
        }
        return byModel.entrySet().stream()
                .map(entry -> entry.getValue().toDto(entry.getKey()))
                .sorted(Comparator.comparingLong(ModelUsage::totalTokens).reversed())
                .toList();
    }

    private static long windowStartHours(int requestedHours) {
        return System.currentTimeMillis() - (long) clamp(requestedHours, 1, 168) * 3_600_000L;
    }

    private static long windowStartDays(int requestedDays) {
        return System.currentTimeMillis() - (long) clamp(requestedDays, 1, 90) * 86_400_000L;
    }

    private static long startOfToday() {
        return Instant.now().atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
    }

    private static long truncateHour(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
    }

    private static long truncateDay(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
    }

    private static String labelHour(long epochMs) {
        ZonedDateTime time = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d:00", time.getHour());
    }

    private static String labelDay(long epochMs) {
        ZonedDateTime time = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d-%02d", time.getMonthValue(), time.getDayOfMonth());
    }

    private static long number(Object[] values, int index) {
        return values == null || index >= values.length || values[index] == null
                ? 0L
                : ((Number) values[index]).longValue();
    }

    /** Hibernate may wrap a multi-column aggregate row in one outer array. */
    private static Object[] unwrapAggregateRow(Object[] values) {
        if (values != null && values.length == 1 && values[0] instanceof Object[] row) {
            return row;
        }
        return values;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
    private static long nonNegative(long value) { return Math.max(0L, value); }

    public record UsageEvent(
            String tenantId,
            String userId,
            String agentId,
            String sessionKey,
            String modelId,
            long inputTokens,
            long outputTokens,
            long cachedPromptTokens,
            long durationMs,
            long costMicrousd,
            String outcome,
            long recordedAtMs) {}

    public record BucketCount(long epochMs, String label, long count) {}
    public record GroupCount(String key, long count) {}
    public record ModelUsage(
            String modelId,
            long turns,
            long inputTokens,
            long outputTokens,
            long cachedPromptTokens,
            long totalTokens,
            long costMicrousd,
            long avgDurationMs) {}

    public record UsageSummary(
            long totalTurns,
            long todayTurns,
            long inputTokens,
            long outputTokens,
            long cachedPromptTokens,
            long totalTokens,
            long totalCostMicrousd,
            long avgDurationMs,
            long uniqueUsers) {}

    private static final class ModelAccumulator {
        private long turns;
        private long inputTokens;
        private long outputTokens;
        private long cachedPromptTokens;
        private long costMicrousd;
        private long totalDurationMs;

        private void add(UsageEventEntity event) {
            turns++;
            inputTokens += event.getInputTokens();
            outputTokens += event.getOutputTokens();
            cachedPromptTokens += event.getCachedPromptTokens();
            costMicrousd += event.getCostMicrousd();
            totalDurationMs += event.getDurationMs();
        }

        private ModelUsage toDto(String modelId) {
            return new ModelUsage(
                    modelId,
                    turns,
                    inputTokens,
                    outputTokens,
                    cachedPromptTokens,
                    inputTokens + outputTokens,
                    costMicrousd,
                    turns == 0 ? 0 : totalDurationMs / turns);
        }
    }
}
