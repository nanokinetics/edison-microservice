package de.otto.edison.jobs.service;

import de.otto.edison.jobs.definition.JobDefinition;
import de.otto.edison.jobs.domain.JobInfo;
import de.otto.edison.jobs.domain.JobMessage;
import de.otto.edison.jobs.domain.Level;
import de.otto.edison.jobs.domain.RunningJob;
import de.otto.edison.jobs.repository.JobBlockedException;
import de.otto.edison.jobs.repository.JobRepository;
import de.otto.edison.status.domain.SystemInfo;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static de.otto.edison.jobs.domain.JobInfo.Builder;
import static de.otto.edison.jobs.domain.JobInfo.JobStatus;
import static de.otto.edison.jobs.domain.JobInfo.JobStatus.ERROR;
import static de.otto.edison.jobs.domain.JobInfo.newJobInfo;
import static de.otto.edison.jobs.domain.JobMessage.jobMessage;
import static de.otto.edison.jobs.domain.Level.WARNING;
import static de.otto.edison.jobs.service.JobRunner.newJobRunner;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.time.OffsetDateTime.now;
import static java.util.Collections.emptyList;

@Service
public class JobService {

    private static final Logger LOG = LoggerFactory.getLogger(JobService.class);

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobMetaService jobMetaService;
    @Autowired
    private ScheduledExecutorService executor;
    @Autowired(required = false)
    private List<JobRunnable> jobRunnables = emptyList();
    @Autowired
    private UuidProvider uuidProvider;


    @Autowired
    private SystemInfo systemInfo;

    private Clock clock = Clock.systemDefaultZone();

    public JobService() {
    }

    JobService(final JobRepository jobRepository,
               final JobMetaService jobMetaService,
               final List<JobRunnable> jobRunnables,
               final ScheduledExecutorService executor,
               final ApplicationEventPublisher applicationEventPublisher,
               final Clock clock,
               final SystemInfo systemInfo,
               final UuidProvider uuidProvider) {
        this.jobRepository = jobRepository;
        this.jobMetaService = jobMetaService;
        this.jobRunnables = jobRunnables;
        this.executor = executor;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = clock;
        this.systemInfo = systemInfo;
        this.uuidProvider = uuidProvider;
    }

    @PostConstruct
    public void postConstruct() {
        LOG.info("Found {} JobRunnables: {}", +jobRunnables.size(), jobRunnables.stream().map(j -> j.getJobDefinition().jobType()).collect(Collectors.toList()));
    }

    /**
     * Starts a job asynchronously in the background.
     *
     * @param jobType the type of the job
     * @return the URI under which you can retrieve the status about the triggered job instance
     */
    public Optional<String> startAsyncJob(String jobType) {
        try {
            final JobRunnable jobRunnable = findJobRunnable(jobType);
            final JobInfo jobInfo = createJobInfo(jobType);
            jobMetaService.aquireRunLock(jobInfo.getJobId(), jobInfo.getJobType());
            jobRepository.createOrUpdate(jobInfo);
            return Optional.of(startAsync(metered(jobRunnable), jobInfo.getJobId()));
        } catch (JobBlockedException e) {
            LOG.info(e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<JobInfo> findJob(final String id) {
        return jobRepository.findOne(id);
    }

    /**
     * Find the latest jobs, optionally restricted to jobs of a specified type.
     *
     * @param type  if provided, the last N jobs of the type are returned, otherwise the last jobs of any type.
     * @param count the number of jobs to return.
     * @return a list of JobInfos
     */
    public List<JobInfo> findJobs(final Optional<String> type, final int count) {
        if (type.isPresent()) {
            return jobRepository.findLatestBy(type.get(), count);
        } else {
            return jobRepository.findLatest(count);
        }
    }

    public List<JobInfo> findJobsDistinct() {
        return jobRepository.findLatestJobsDistinct();
    }

    public void deleteJobs(final Optional<String> type) {
        if (type.isPresent()) {
            jobRepository.findByType(type.get()).forEach((j) -> jobRepository.removeIfStopped(j.getJobId()));
        } else {
            jobRepository.findAll().forEach((j) -> jobRepository.removeIfStopped(j.getJobId()));
        }
    }

    public void stopJob(final String jobId) {
        stopJob(jobId, null);
    }

    public void killJobsDeadSince(final int seconds) {
        final OffsetDateTime timeToMarkJobAsStopped = now(clock).minusSeconds(seconds);
        LOG.info(format("JobCleanup: Looking for jobs older than %s ", timeToMarkJobAsStopped));
        final List<JobInfo> deadJobs = jobRepository.findRunningWithoutUpdateSince(timeToMarkJobAsStopped);
        deadJobs.forEach(deadJob -> killJob(deadJob.getJobId()));
        clearRunLocks();
    }

    /**
     * Checks all run locks and releases the lock, if the job is stopped.
     *
     * TODO: This method should never do something, otherwise the is a bug in the lock handling.
     * TODO: Check Log files + Remove
     */
    private void clearRunLocks() {
        jobMetaService.runningJobs().forEach((RunningJob runningJob) -> {
            final Optional<JobInfo> jobInfoOptional = jobRepository.findOne(runningJob.jobId);
            if (jobInfoOptional.isPresent() && jobInfoOptional.get().isStopped()) {
                jobMetaService.releaseRunLock(runningJob.jobType);
                LOG.error("Clear Lock of Job {}. Job stopped already.", runningJob.jobType);
            } else if (!jobInfoOptional.isPresent()){
                jobMetaService.releaseRunLock(runningJob.jobType);
                LOG.error("Clear Lock of Job {}. JobID does not exist", runningJob.jobType);
            }
        });
    }

    public void killJob(final String jobId) {
        stopJob(jobId, JobStatus.DEAD);
        jobRepository.appendMessage(
                jobId,
                jobMessage(WARNING, "Job didn't receive updates for a while, considering it dead", now(clock))
        );
    }

    private void stopJob(final String jobId,
                         final JobStatus status) {
        jobRepository.findOne(jobId).ifPresent((JobInfo jobInfo) -> {
            jobMetaService.releaseRunLock(jobInfo.getJobType());
            final OffsetDateTime now = now(clock);
            final Builder builder = jobInfo.copy()
                    .setStopped(now)
                    .setLastUpdated(now);
            if (status != null) {
                builder.setStatus(status);
            }
            jobRepository.createOrUpdate(builder.build());
        });
    }

    public void appendMessage(final String jobId,
                              final JobMessage jobMessage) {
        // TODO: Refactor JobRepository so only a single update is required
        jobRepository.appendMessage(jobId, jobMessage);
        if (jobMessage.getLevel() == Level.ERROR) {
            jobRepository.findOne(jobId).ifPresent(jobInfo -> {
                jobRepository.createOrUpdate(
                        jobInfo.copy()
                                .setStatus(ERROR)
                                .setLastUpdated(now(clock))
                                .build());
            });
        }
    }

    public void keepAlive(final String jobId) {
        jobRepository.setLastUpdate(jobId, now(clock));
    }

    public void markSkipped(final String jobId) {
        // TODO: Refactor JobRepository so only a single update is required
        OffsetDateTime currentTimestamp = now(clock);
        jobRepository.appendMessage(jobId, jobMessage(Level.INFO, "Skipped job ..", currentTimestamp));
        jobRepository.setLastUpdate(jobId, currentTimestamp);
        jobRepository.setJobStatus(jobId, JobStatus.SKIPPED);
    }

    public void markRestarted(final String jobId) {
        // TODO: Refactor JobRepository so only a single update is required
        OffsetDateTime currentTimestamp = now(clock);
        jobRepository.appendMessage(jobId, jobMessage(WARNING, "Restarting job ..", currentTimestamp));
        jobRepository.setLastUpdate(jobId, currentTimestamp);
        jobRepository.setJobStatus(jobId, JobStatus.OK);
    }

    private JobInfo createJobInfo(final String jobType) {
        return newJobInfo(uuidProvider.getUuid(), jobType, clock,
                systemInfo.getHostname());
    }

    private JobRunnable findJobRunnable(final String jobType) {
        final Optional<JobRunnable> optionalRunnable = jobRunnables.stream().filter(r -> r.getJobDefinition().jobType().equalsIgnoreCase(jobType)).findFirst();
        return optionalRunnable.orElseThrow(() -> new IllegalArgumentException("No JobRunnable for " + jobType));
    }

    private String startAsync(final JobRunnable jobRunnable,
                              final String jobId) {
        executor.execute(newJobRunner(
                jobId,
                jobRunnable,
                applicationEventPublisher,
                executor
        ));
        return jobId;
    }

    private JobRunnable metered(final JobRunnable delegate) {
        return new JobRunnable() {

            @Override
            public JobDefinition getJobDefinition() {
                return delegate.getJobDefinition();
            }

            @Override
            public boolean execute() {
                long ts = currentTimeMillis();
                boolean executed = delegate.execute();
                Metrics.gauge(gaugeName(), (currentTimeMillis() - ts) / 1000L);
                return executed;
            }

            private String gaugeName() {
                return "gauge.jobs.runtime." + delegate.getJobDefinition().jobType().toLowerCase();
            }
        };
    }

}
