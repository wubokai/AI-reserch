package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.persistence.ResearchStepEntity;
import com.aiquantresearch.api.research.persistence.ResearchStepRepository;
import com.aiquantresearch.api.research.persistence.StepAttemptEntity;
import com.aiquantresearch.api.research.persistence.StepAttemptRepository;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchQueryService {

    private static final String MOCK_WARNING = "DEMO DATA - NOT REAL MARKET DATA";
    private static final String MIXED_TEST_WARNING =
            "MIXED_TEST DATA - TEST ONLY; PUBLISHING AND EXPORT ARE PROHIBITED";

    private final ResearchJobRepository researchJobRepository;
    private final ResearchStepRepository researchStepRepository;
    private final StepAttemptRepository stepAttemptRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ResearchQueryService(
            ResearchJobRepository researchJobRepository,
            ResearchStepRepository researchStepRepository,
            StepAttemptRepository stepAttemptRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.researchJobRepository = researchJobRepository;
        this.researchStepRepository = researchStepRepository;
        this.stepAttemptRepository = stepAttemptRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ResearchPageView list(UUID ownerId, ResearchListQuery query) {
        Sort primarySort = query.sort().ascending()
                ? Sort.by(query.sort().property()).ascending()
                : Sort.by(query.sort().property()).descending();
        Sort sort = primarySort.and(query.sort().ascending()
                ? Sort.by("id").ascending()
                : Sort.by("id").descending());
        var pageable = PageRequest.of(query.page(), query.size(), sort);
        String symbol = query.symbol();
        ResearchStatus status = query.status();
        Instant from = query.from();
        Instant to = query.to();
        String queryText = query.query();
        var page = researchJobRepository.searchOwned(
                ownerId,
                symbol != null,
                symbol == null ? "" : symbol,
                status != null,
                status == null ? ResearchStatus.CREATED : status,
                from != null,
                from == null ? Instant.EPOCH : from,
                to != null,
                to == null ? Instant.EPOCH : to,
                queryText != null,
                queryText == null ? "" : queryText,
                pageable
        );
        List<ResearchItemView> items = page.getContent().stream()
                .map(this::toItem)
                .toList();
        return new ResearchPageView(
                items,
                new PageMetadataView(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages(),
                        page.isFirst(),
                        page.isLast()
                )
        );
    }

    @Transactional(readOnly = true)
    public ResearchDetailView detail(UUID ownerId, UUID researchId) {
        ResearchJobEntity research = findOwned(ownerId, researchId);
        List<ResearchStepEntity> steps = researchStepRepository.findAllOwned(researchId, ownerId);
        Map<UUID, List<StepAttemptEntity>> attempts = attemptsByStep(steps);
        ErrorSummaryView lastError = steps.stream()
                .sorted(Comparator.comparingInt(ResearchStepEntity::getSequenceNo).reversed())
                .map(step -> toError(step, latestAttempt(attempts.get(step.getId()))))
                .filter(error -> error != null)
                .findFirst()
                .orElse(null);
        List<String> warnings = switch (research.getDataMode()) {
            case MOCK -> List.of(MOCK_WARNING);
            case MIXED_TEST -> List.of(MIXED_TEST_WARNING);
            case REAL -> List.of();
        };
        return new ResearchDetailView(
                toItem(research),
                readRequest(research),
                research.getCurrentStep(),
                research.getStartedAt(),
                research.isCancellationRequested(),
                lastError,
                warnings
        );
    }

    @Transactional(readOnly = true)
    public ResearchStatusView status(UUID ownerId, UUID researchId) {
        ResearchJobEntity research = findOwned(ownerId, researchId);
        List<ResearchStepEntity> steps = researchStepRepository.findAllOwned(researchId, ownerId);
        Map<UUID, List<StepAttemptEntity>> attempts = attemptsByStep(steps);
        List<ResearchStepView> stepViews = steps.stream()
                .map(step -> toStep(
                        step,
                        attempts.getOrDefault(step.getId(), List.of()),
                        clock.instant()
                ))
                .toList();
        ErrorSummaryView error = stepViews.stream()
                .sorted(Comparator.comparingInt(
                        (ResearchStepView view) -> view.step().sequence()
                ).reversed())
                .map(ResearchStepView::error)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        int completedSteps = (int) steps.stream()
                .filter(step -> step.getStatus().satisfiesDependency())
                .count();
        Instant observedUpdate = latestObservedUpdate(research, steps, attempts);
        return new ResearchStatusView(
                research.getId(),
                research.getStatus(),
                research.getProgress(),
                research.getCurrentStep(),
                completedSteps,
                steps.size(),
                research.isCancellationRequested(),
                research.getDataMode(),
                error,
                stepViews,
                observedUpdate
        );
    }

    ResearchItemView toItem(ResearchJobEntity research) {
        CreateResearchCommand request = readRequest(research);
        String title = research.getQuery().length() <= 120
                ? research.getQuery()
                : research.getQuery().substring(0, 117) + "...";
        return new ResearchItemView(
                research.getId(),
                title,
                research.getQuery(),
                request.symbol(),
                request.companyName(),
                request.benchmark(),
                research.getStatus(),
                research.getProgress(),
                request.reportDepth(),
                research.getDataMode(),
                null,
                research.getCreatedAt(),
                research.getUpdatedAt(),
                research.getCompletedAt()
        );
    }

    private ResearchStepView toStep(
            ResearchStepEntity step,
            List<StepAttemptEntity> attempts,
            Instant observedAt
    ) {
        StepAttemptEntity latest = latestAttempt(attempts);
        var startedAt = attempts.stream()
                .map(StepAttemptEntity::getStartedAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
        var completedAt = step.getStatus().isTerminal()
                ? latest == null || latest.getCompletedAt() == null
                        ? step.getUpdatedAt()
                        : latest.getCompletedAt()
                : null;
        boolean hasCompletedDuration = attempts.stream()
                .anyMatch(attempt -> attempt.getDurationMs() != null);
        long totalDuration = attempts.stream()
                .map(StepAttemptEntity::getDurationMs)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .sum();
        boolean running = latest != null
                && latest.getStatus() == com.aiquantresearch.api.research.domain.AttemptStatus.RUNNING;
        if (running) {
            totalDuration = Math.addExact(
                    totalDuration,
                    Math.max(0, Duration.between(latest.getStartedAt(), observedAt).toMillis())
            );
        }
        Long duration = hasCompletedDuration || running ? totalDuration : null;
        boolean retryable = latest != null
                && latest.isRetryable()
                && (step.hasAttemptBudget() || step.getStatus() == StepStatus.FAILED);
        return new ResearchStepView(
                step.getStepType(),
                step.getStatus(),
                step.getAttemptCount(),
                startedAt,
                completedAt,
                duration,
                retryable,
                toError(step, latest)
        );
    }

    private static ErrorSummaryView toError(
            ResearchStepEntity step,
            StepAttemptEntity latest
    ) {
        if (latest == null || latest.getErrorCode() == null) {
            return null;
        }
        return new ErrorSummaryView(
                latest.getErrorCode(),
                latest.getErrorMessageSafe() == null
                        ? "The step did not complete successfully"
                        : latest.getErrorMessageSafe(),
                latest.isRetryable(),
                step.getStepType()
        );
    }

    private ResearchJobEntity findOwned(UUID ownerId, UUID researchId) {
        return researchJobRepository.findByIdAndOwnerIdAndDeletedAtIsNull(researchId, ownerId)
                .orElseThrow(() -> new ResearchNotFoundException(researchId));
    }

    private CreateResearchCommand readRequest(ResearchJobEntity research) {
        try {
            return objectMapper.readValue(research.getRequestJson(), CreateResearchCommand.class);
        } catch (JsonProcessingException exception) {
            throw new ResearchApplicationException(
                    "RESEARCH_SNAPSHOT_INVALID",
                    "The stored research request snapshot is invalid",
                    false
            );
        }
    }

    private Map<UUID, List<StepAttemptEntity>> attemptsByStep(List<ResearchStepEntity> steps) {
        if (steps.isEmpty()) {
            return Map.of();
        }
        List<UUID> stepIds = steps.stream().map(ResearchStepEntity::getId).toList();
        Map<UUID, List<StepAttemptEntity>> result = new HashMap<>();
        stepAttemptRepository
                .findAllByResearchStepIdInOrderByResearchStepIdAscAttemptNumberAsc(stepIds)
                .forEach(attempt -> result
                        .computeIfAbsent(attempt.getResearchStepId(), ignored -> new ArrayList<>())
                        .add(attempt));
        return result;
    }

    private static Instant latestObservedUpdate(
            ResearchJobEntity research,
            List<ResearchStepEntity> steps,
            Map<UUID, List<StepAttemptEntity>> attempts
    ) {
        Instant latest = research.getUpdatedAt();
        for (ResearchStepEntity step : steps) {
            if (step.getUpdatedAt().isAfter(latest)) {
                latest = step.getUpdatedAt();
            }
            for (StepAttemptEntity attempt : attempts.getOrDefault(step.getId(), List.of())) {
                Instant attemptUpdate = attempt.getCompletedAt() == null
                        ? attempt.getHeartbeatAt()
                        : attempt.getCompletedAt();
                if (attemptUpdate.isAfter(latest)) {
                    latest = attemptUpdate;
                }
            }
        }
        return latest;
    }

    private static StepAttemptEntity latestAttempt(List<StepAttemptEntity> attempts) {
        return attempts == null || attempts.isEmpty() ? null : attempts.getLast();
    }
}
