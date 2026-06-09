
package project.controllers;

import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKClassResult;
import com.github.mauricioaniche.ck.CKMethodResult;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.LoggerFactory;
import project.utils.Projects;
import project.models.*;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import project.statefull.ConstantSize;
import project.statefull.ConstantsWindowsFormat;

import org.slf4j.Logger;

/**
 * Orchestrates the computation of all software metrics for a project's release history.
 *
 * <p>For each release, this class:
 * <ol>
 *   <li>Identifies the commits belonging to that release (and all previous ones).</li>
 *   <li>Checks which commits are already stored in the on-disk cache
 *       ({@link Caching}) to avoid redundant CK analysis.</li>
 *   <li>Checks out uncached commits in parallel, runs the
 *       <a href="https://github.com/mauricioaniche/ck">CK</a> tool on the
 *       checked-out sources, and persists the results back to the cache.</li>
 *   <li>Assigns buggyness to each {@link MethodInstance} based on the
 *       IV–FV range reported by the associated {@link Ticket}s.</li>
 *   <li>Computes file-level metrics (NR, age, nAuth) by replaying the commit
 *       history across releases.</li>
 *   <li>Writes the final dataset rows via {@link ClassWriter}.</li>
 * </ol>
 *
 * <p><b>Thread safety:</b> commit processing runs inside a {@link ForkJoinPool}.
 * Shared state ({@code releaseResults}, {@code resultCommitsMethods}) uses
 * {@link ConcurrentHashMap}; repository checkout is serialised with an explicit
 * monitor lock ({@code threadLock}).
 */
public class MetricsCalculator {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsCalculator.class);

    /** Temporary directory used by the CK tool to store checked-out source files. */
    private final Path tempDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "ck_analysis");

    /** Full ordered list of releases being processed; set once in {@link #calculateAll}. */
    private List<Release> releaseList;

    /** Low-level git operations (checkout, diff, commit listing). */
    private final GitHubInfoRetrieve gitHubInfoRetrieve;

    /** Project name used as a key for cache files and CSV output. */
    private final String projectName;

    /** Handles repository backup/restore and git checkout strategies. */
    private final RepositoryManager repositoryManager;

    /**
     * Dirty flag: set to {@code true} whenever new metric results are added so that
     * {@link #assignBuggyness} knows it must re-evaluate and is not a no-op.
     */
    private boolean resultsChanged;

    /**
     * Persistent metric cache keyed by commit SHA-1 hash.
     * Inner map: method key → {@link MethodInstance} with all CK metrics filled.
     * Loaded from disk at construction and flushed periodically during processing.
     */
    private final Map<String, Map<String, MethodInstance>> resultCommitsMethods = new ConcurrentHashMap<>();

    /** Project-specific configuration (split ratio, refactored release name, etc.). */
    private final Projects project;

    /**
     * Constructs a {@code MetricsCalculator} for the given project, initialising
     * the temporary and cache directories and loading the existing commit cache from disk.
     *
     * @param gitHubInfoRetrieve facade for git repository operations
     * @param projectName        project identifier (e.g. {@code "bookkeeper"})
     * @param project            enum entry carrying project-specific configuration
     * @throws IOException if the temporary or cache directories cannot be created
     */
    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve, String projectName, Projects project) throws IOException {
        this.gitHubInfoRetrieve = gitHubInfoRetrieve;
        this.projectName = projectName;
        Files.createDirectories(Paths.get(String.valueOf(tempDirPath)));
        Path cacheDirPath = ConstantsWindowsFormat.CACHE_PATH;
        Files.createDirectories(cacheDirPath);
        this.repositoryManager = new RepositoryManager(gitHubInfoRetrieve);
        this.resultsChanged = false;
        this.project = project;
        Caching.loadCommitCache(resultCommitsMethods, null, projectName);
    }

    // -------------------------------------------------------------------------
    // Inner data classes
    // -------------------------------------------------------------------------

    /**
     * Mutable value object that groups all the state needed to process a single
     * release.  Passed through the processing pipeline so every step can read
     * and update the same shared context without a long parameter list.
     */
    public static class ReleaseData {
        /** The release being analysed. */
        Release release;
        /** Accumulated method metrics for every commit up to this release; thread-safe. */
        ConcurrentMap<String, MethodInstance> releaseResults;
        /** Maps each {@link RevCommit} to the release it logically belongs to. */
        Map<RevCommit, Release> mapCommitRelease;
        /** Ordered list of commits in scope for this release. */
        List<RevCommit> releaseCommits;
        /** Commits whose CK metrics have already been computed (from cache or live). */
        Map<String, RevCommit> commitsAnalyzed;
        /** SHA-1 hashes of commits that still need CK analysis. */
        Set<String> commitHashesToProcess;
        /** Lookup map: commit SHA-1 → {@link RevCommit} object. */
        Map<String, RevCommit> commitsByHash;
        /** Bug-fixing tickets associated with this release, used for buggyness labelling. */
        List<Ticket> releaseTickets;
        /** Whether to write a training, test, or partial CSV file. */
        DataSetType dataSetType;
    }

    /**
     * Tracks which commits belong to a release and whether their CK metrics are
     * already available from the cache.  Used by {@link #populateReferenceMap}.
     */
    Map<Release, Set<CommitCheck>> referenceMap = new HashMap<>();

    /**
     * Associates a single {@link RevCommit} with the (possibly cached) set of
     * {@link MethodInstance} metrics produced when that commit was last analysed.
     * {@code methods} is {@code null} when no cached data exists yet.
     */
    private static class CommitCheck {
        private final RevCommit commit;
        private Set<MethodInstance> methods;

        CommitCheck(RevCommit commit) {
            this.commit = commit;
            this.methods = null;
        }

        /**
         * Attaches a collection of pre-computed method metrics to this commit.
         *
         * @param methods metrics loaded from the on-disk cache
         */
        void addMethods(Collection<MethodInstance> methods) {
            if (this.methods == null) this.methods = new HashSet<>();
            this.methods.addAll(methods);
        }
    }

    // -------------------------------------------------------------------------
    // Cache and reference-map helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures that every release preceding {@code curRelease} has an entry in
     * {@link #referenceMap}.  Each entry maps the release to a set of
     * {@link CommitCheck} objects — one per commit — with cached metrics
     * pre-populated where available.
     *
     * <p>This method is idempotent: releases already present in the map are
     * left untouched.
     *
     * @param curRelease the release currently being processed; only releases
     *                   with a lower id are considered
     */
    void populateReferenceMap(Release curRelease) {
        List<Release> relevantReleases = releaseList.stream()
                .filter(r -> r.getId() < curRelease.getId())
                .toList();

        for (Release release : relevantReleases) {
            if (!referenceMap.containsKey(release)) {
                Set<CommitCheck> releaseCommits = new HashSet<>();
                for (RevCommit commit : release.getAllReleaseCommits()) {
                    String commitHash = commit.getId().getName();
                    CommitCheck commitCheck = new CommitCheck(commit);
                    if (resultCommitsMethods.containsKey(commitHash)) {
                        commitCheck.addMethods(resultCommitsMethods.get(commitHash).values());
                    }
                    releaseCommits.add(commitCheck);
                }
                referenceMap.put(release, releaseCommits);
            }
        }
    }

    /**
     * Logs, at DEBUG level, how many methods in {@code releaseData.releaseResults}
     * belong to each release.  Used to verify cache-hydration correctness.
     *
     * @param releaseData the release context whose accumulated results are inspected
     */
    void logReleaseResults(ReleaseData releaseData) {
        LOGGER.info(" after the association commit in cache ");
        for (Release release : releaseList) {
            int count = 0;
            for (MethodInstance method : releaseData.releaseResults.values()) {
                if (method.getReleaseName().equals(release.getName())) {
                    count++;
                }
            }
            LOGGER.debug("found methods {} for the release {} ", count, release.getName());
        }
    }

    /**
     * Separates the commits for all releases preceding {@code releaseData.release}
     * into two buckets:
     * <ul>
     *   <li><b>Cache hit</b> — metrics are loaded directly into
     *       {@code releaseData.releaseResults} and the commit is added to
     *       {@code commitsAnalyzed}.</li>
     *   <li><b>Cache miss</b> — the commit SHA-1 is added to
     *       {@code commitHashesToProcess} for later CK analysis.</li>
     * </ul>
     *
     * @param releaseData the release context to populate
     */
    void getCommitsInCache(ReleaseData releaseData) {
        populateReferenceMap(releaseData.release);
        List<Release> relevantReleases = releaseList.stream()
                .filter(r -> r.getId() < releaseData.release.getId())
                .toList();

        LOGGER.info(" after the association commit in cache ");
        for (Release release : relevantReleases) {
            for (CommitCheck commitCheck : referenceMap.get(release)) {
                String commitHash = commitCheck.commit.getId().getName();
                releaseData.commitsByHash.put(commitHash, commitCheck.commit);
                if (commitCheck.methods == null) {
                    releaseData.commitHashesToProcess.add(commitHash);
                    continue;
                }
                Map<String, MethodInstance> commitMetrics = new HashMap<>();
                for (MethodInstance method : commitCheck.methods) {
                    method.setReleaseName(release.getName());
                    String classKey = ClassFile.getKey(method.getClassPath(), method.getClassName());
                    ClassFile classFile = release.getClassFileByKey(classKey);
                    if (classFile != null) classFile.addMethod(method);
                    commitMetrics.put(MethodInstance.createMethodKey(method), method);
                }
                releaseData.releaseResults.putAll(commitMetrics);
                releaseData.commitsAnalyzed.put(commitHash, commitCheck.commit);
                releaseData.releaseCommits.add(commitCheck.commit);
                resultsChanged = true;
            }
        }
        logReleaseResults(releaseData);
        resultsChanged = true;
    }

    // -------------------------------------------------------------------------
    // Parallel commit processing
    // -------------------------------------------------------------------------

    /**
     * Processes all cache-miss commits in {@code releaseData.commitHashesToProcess}
     * using a {@link ForkJoinPool}, batching them to limit peak memory consumption.
     *
     * <p>For each commit the method:
     * <ol>
     *   <li>Checks out the commit into a temporary directory (serialised via
     *       {@code threadLock} to avoid concurrent git operations).</li>
     *   <li>Runs {@link #calculateCKMetrics} on the checked-out source tree.</li>
     *   <li>Merges the resulting metrics into {@code releaseData.releaseResults}.</li>
     *   <li>Periodically flushes the cache and writes partial CSV output via
     *       {@link #outData}.</li>
     * </ol>
     *
     * <p>If a batch fails, {@link #handleProcessingError} attempts a full repository
     * reset and the remaining commits are retried recursively.
     *
     * @param releaseData release context carrying the commit list and result maps
     * @throws IOException          if a git or file-system operation fails unrecoverably
     * @throws ExecutionException   if a thread inside the pool throws
     * @throws InterruptedException if the calling thread is interrupted while waiting
     *                              for a batch to complete
     */
    void processCommits(ReleaseData releaseData) throws IOException, ExecutionException, InterruptedException {
        LOGGER.info("processing the other commits ");
        Object threadLock = new Object();

        AtomicInteger countThread = new AtomicInteger();
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int memoryBasedThreads = (int) Math.max(1, Math.min(maxMemory / 512, ConstantSize.NUM_THREADS));
        int numThreads = Math.min(availableProcessors, memoryBasedThreads);

        LOGGER.info("Maximum available memory: {} MB, Number of threads: {}", maxMemory, numThreads);

        List<String> commitHashList = new ArrayList<>(releaseData.commitHashesToProcess);
        int batchSize = Math.min(100, commitHashList.size());

        repositoryManager.backupRepository();

        int currentBatchIndex = 0;
        boolean restartProcessing = false;
        ForkJoinPool finalThreadPool = null;

        String finalCommitHash = commitHashList.get(commitHashList.size() - 1);

        while (currentBatchIndex < commitHashList.size()) {
            restartProcessing = false;
            ForkJoinPool customThreadPool = new ForkJoinPool();
            try {
                finalThreadPool = customThreadPool;
                int endIndex = Math.min(currentBatchIndex + batchSize, commitHashList.size());
                List<String> batchCommits = commitHashList.subList(currentBatchIndex, endIndex);

                LOGGER.info("Processing commit batch {}/{} (size: {})",
                        (currentBatchIndex / batchSize) + 1,
                        (int) Math.ceil(commitHashList.size() / (double) batchSize),
                        batchCommits.size());
                try {
                    customThreadPool.submit(() ->
                            batchCommits.parallelStream().forEach(commitHash -> {
                                try {
                                    RevCommit commit = releaseData.commitsByHash.get(commitHash);
                                    Path commitTempDir = tempDirPath.resolve(releaseData.release.getName() + "_" + commitHash);
                                    releaseData.commitsAnalyzed.put(commitHash, commit);

                                    synchronized (threadLock) {
                                        repositoryManager.checkoutRelease(commit, commitTempDir, commitHash.equals(finalCommitHash));
                                    }

                                    countThread.getAndIncrement();
                                    Release curRelease = releaseData.mapCommitRelease.get(commit);
                                    if (curRelease == null) {
                                        curRelease = releaseData.release;
                                    }
                                    Map<String, MethodInstance> commitMetrics = calculateCKMetrics(commit, commitTempDir, curRelease);
                                    resultCommitsMethods.put(commitHash, commitMetrics);
                                    synchronized (threadLock) {
                                        releaseData.releaseResults.putAll(commitMetrics);
                                    }
                                    synchronized (threadLock) {
                                        resultsChanged = true;
                                        outData(countThread.get(), releaseData);
                                    }

                                    repositoryManager.cleanupTempDirectory(commitTempDir);

                                } catch (OutOfMemoryError | Exception e) {
                                    LOGGER.error("Error during commit processing: {} , possible insufficient memory", commitHash, e);
                                }
                            })
                    ).get();
                } catch (Exception e) {
                    LOGGER.error("Error during batch processing: {}", e.getMessage(), e);
                    customThreadPool.shutdown();
                    restartProcessing = handleProcessingError();
                }
                Caching.saveCommitCache(resultCommitsMethods, projectName);
                currentBatchIndex += batchSize;
            } finally {
                customThreadPool.shutdown();
            }
        }

        if (restartProcessing) {
            LOGGER.info("Restarting processing after complete reset...");
            releaseData.commitHashesToProcess.removeAll(releaseData.commitsAnalyzed.keySet());
            processCommits(releaseData);
            return;
        }

        if (finalThreadPool == null) {
            LOGGER.warn("Thread pool not available, sequential processing");
            Caching.saveCommitCache(resultCommitsMethods, projectName);
            repositoryManager.restoreFromBackup();
        } else {
            Caching.saveCommitCache(resultCommitsMethods, projectName);
            assignBuggyness(releaseData);
            ClassWriter.writeResultsToFile(releaseData.release, projectName, releaseData.releaseResults, releaseData.dataSetType);
        }
    }

    /** Guards against concurrent invocations of {@link #handleProcessingError}. */
    private volatile boolean resetInProgress = false;

    /**
     * Handles a fatal error during commit processing by restoring the repository
     * to a clean state from the most recent backup, then creating a new backup so
     * that subsequent retries start from a consistent baseline.
     *
     * <p>The method also saves any metrics already accumulated to avoid losing work.
     *
     * @return {@code true} if the reset was executed successfully;
     *         {@code false} if a concurrent reset was already in progress or if
     *         the restoration itself threw an exception
     */
    private boolean handleProcessingError() {
        boolean resetPerformed = false;
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory() / (1024 * 1024);
            long freeMemory = runtime.freeMemory() / (1024 * 1024);
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory() / (1024 * 1024);

            LOGGER.info("Memory state during error: Used {}MB, Free {}MB, Total {}MB, Max {}MB",
                    usedMemory, freeMemory, totalMemory, maxMemory);

            if (!resetInProgress) {
                resetInProgress = true;
                LOGGER.warn("Error detected. Executing complete reset...");
                Thread.sleep(10000);
                gitHubInfoRetrieve.initializingRepo();
                LOGGER.info("Creating a new backup after reset...");
                repositoryManager.backupRepository();
                if (!resultCommitsMethods.isEmpty()) {
                    LOGGER.info("Saving current state after reset...");
                    Caching.saveCommitCache(resultCommitsMethods, projectName);
                }
                resetPerformed = true;
                resetInProgress = false;
                LOGGER.info("Complete reset executed successfully. Restarting processing...");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Operation interrupted during error handling", e);
        } catch (Exception restoreError) {
            LOGGER.error("Error during backup restoration: {}", restoreError.getMessage(), restoreError);
        }
        return resetPerformed;
    }

    // -------------------------------------------------------------------------
    // Periodic logging / partial output
    // -------------------------------------------------------------------------

    /**
     * Emits progress logs and triggers periodic cache flushes and partial CSV writes.
     * Called after each commit is processed inside the parallel pool.
     *
     * <ul>
     *   <li>Every {@link ConstantSize#FREQUENCY_LOG} commits: logs progress counters.</li>
     *   <li>Every {@link ConstantSize#FREQUENCY_WRITE_CACHE} commits: flushes the cache.</li>
     *   <li>Every {@link ConstantSize#FREQUENCY_WRITE_CSV} commits: writes a partial CSV.</li>
     * </ul>
     *
     * @param log         the current number of commits processed so far
     * @param releaseData the release context used for partial buggyness assignment and output
     */
    private void outData(int log, ReleaseData releaseData) {
        if ((log % ConstantSize.FREQUENCY_LOG) == 0) {
            int processedCommits = releaseData.commitsAnalyzed.size();
            int remainingCommits = 0;
            for (String hash : releaseData.commitHashesToProcess) {
                if (!releaseData.commitsAnalyzed.containsKey(hash)) {
                    remainingCommits++;
                }
            }
            LOGGER.info("\n\n  Release {} Thread {} in progress... commits analyzed {}  commits to process {}) \n\n",
                    releaseData.release.getName(), log, processedCommits, remainingCommits);
        }
        if ((log % ConstantSize.FREQUENCY_WRITE_CACHE) == 0) {
            Caching.saveCommitCache(resultCommitsMethods, projectName);
        }
        if ((log % ConstantSize.FREQUENCY_WRITE_CSV) == 0) {
            assignBuggyness(releaseData);
            ClassWriter.writeResultsToFile(releaseData.release, projectName, releaseData.releaseResults, DataSetType.PARTIAL);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Entry point for computing all metrics for a single release.
     *
     * <p>The method:
     * <ol>
     *   <li>Builds a {@link ReleaseData} context containing the commit set for
     *       all releases up to (but not including) this one.</li>
     *   <li>Hydrates the context from the cache via {@link #getCommitsInCache}.</li>
     *   <li>If any commits are missing from the cache, calls {@link #processCommits}
     *       (with up to 3 retries on error).</li>
     *   <li>Calls {@link #assignBuggyness} to label methods in the IV–FV window.</li>
     * </ol>
     *
     * @param release        the release for which metrics should be computed
     * @param releaseTickets bug-fixing tickets associated with this release
     * @param dataSetType    controls whether the output file is a train, test, or partial CSV
     */
    public void calculateReleaseMetrics(Release release, List<Ticket> releaseTickets, DataSetType dataSetType) {
        ReleaseData data = new ReleaseData();
        data.release = release;
        data.releaseResults = new ConcurrentHashMap<>();
        data.releaseTickets = releaseTickets;
        data.dataSetType = dataSetType;
        LOGGER.info((" \n\n starting metrics calculation for release " + release.getName()));

        data.mapCommitRelease = filterCommitsByRelease(release);
        List<RevCommit> passingList = new ArrayList<>(data.mapCommitRelease.keySet());
        int startIndex = Math.max(0, passingList.size() - ConstantSize.NUM_COMMITS);
        data.releaseCommits = passingList.subList(startIndex, passingList.size());
        LOGGER.info("number of commit to check: {}", data.releaseCommits.size());
        data.commitsAnalyzed = new HashMap<>();
        data.commitHashesToProcess = new HashSet<>();
        data.commitsByHash = new HashMap<>();
        getCommitsInCache(data);
        int cachedCommitsSize = data.commitsAnalyzed.size();
        LOGGER.info("Found {} commits in cache, need to process {} commits",
                cachedCommitsSize, data.commitHashesToProcess.size());

        if (data.commitHashesToProcess.isEmpty()) {
            LOGGER.info("No commits to process for release ");
            assignBuggyness(data);
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, dataSetType);
            return;
        }
        if (!data.releaseResults.isEmpty() && data.commitHashesToProcess.size() > ConstantSize.FREQUENCY_WRITE_CSV) {
            LOGGER.info("writing before the processing");
            assignBuggyness(data);
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, DataSetType.PARTIAL);
        }

        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    LOGGER.info("Attempt {} of {} for processing release {}",
                            attempt + 1, maxRetries, release.getName());
                }
                processCommits(data);
                assignBuggyness(data);
                LOGGER.info("Processing of release {} completed successfully", release.getName());
                break;
            } catch (IOException | ExecutionException e) {
                LOGGER.error("Error during processing of release {}: {}",
                        release.getName(), e.getMessage(), e);
                boolean resetPerformed = handleProcessingError();
                if (!resetPerformed && attempt == maxRetries - 1) {
                    LOGGER.error("Unable to complete processing of release {} after {} attempts",
                            release.getName(), maxRetries);
                    Caching.saveCommitCache(resultCommitsMethods, projectName);
                    assignBuggyness(data);
                    ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, dataSetType);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("Processing interrupted for release {}", release.getName(), e);
                handleProcessingError();
                LOGGER.warn("Processing of release {} interrupted by user", release.getName());
            }
        }
    }

    /**
     * Top-level orchestrator: iterates over all releases, computing file-level metrics
     * (NR, creation date, nAuth) by replaying the commit history, then delegates
     * CK + buggyness computation to {@link #calculateReleaseMetrics}.
     *
     * <p>After all per-release processing, {@link #calculateAge} is called to
     * compute the age (in days) of each class file.
     *
     * @param releaseList ordered list of releases to process (must already be sorted
     *                    by date as produced by {@link JiraInfoRetrieve#sortReleaseList})
     */
    public void calculateAll(List<Release> releaseList) {
        LOGGER.info("processing class metrics");
        this.releaseList = releaseList;
        processReleases(releaseList);
        calculateAge(releaseList);
        LOGGER.info("end of processing class metrics");
    }

    // -------------------------------------------------------------------------
    // File-level metric computation (NR, creation date, nAuth)
    // -------------------------------------------------------------------------

    /**
     * Iterates over all releases in order, computing per-class NR and creation-date
     * metrics by replaying each release's commit history.
     *
     * @param releaseList ordered release list
     */
    private void processReleases(List<Release> releaseList) {
        RevCommit veryFirstCommit = null;
        int len = releaseList.size();
        for (int i = 0; i < len; i++) {
            Release currRelease = releaseList.get(i);
            copyPreviousReleaseRevisions(i, currRelease, releaseList);
            processCommitsForRelease(i, currRelease, veryFirstCommit);
        }
    }

    /**
     * Carries the NR (number of revisions) counter forward from the previous release
     * to the current one so that cumulative revision counts are preserved across
     * the walk-forward splits.
     *
     * @param currentIndex  0-based index of the current release in the list
     * @param currentRelease the release being initialised
     * @param releaseList   full ordered release list
     */
    private void copyPreviousReleaseRevisions(int currentIndex, Release currentRelease, List<Release> releaseList) {
        if (currentIndex > 0) {
            Release prevRelease = releaseList.get(currentIndex - 1);
            copyRevisions(currentRelease, prevRelease);
        }
    }

    /**
     * Copies the NR counter from every class file in {@code previousRelease} to the
     * matching class file (by key) in {@code currentRelease}.
     *
     * @param currentRelease  destination release
     * @param previousRelease source release
     */
    private void copyRevisions(Release currentRelease, Release previousRelease) {
        for (ClassFile currFile : currentRelease.getReleaseAllClass()) {
            String classKey = ClassFile.getKey(currFile);
            ClassFile prevFile = previousRelease.getClassFileByKey(classKey);
            copyFileRevisions(currFile, prevFile);
        }
    }

    /**
     * Increments {@code currentFile}'s NR counter by however many revisions
     * {@code previousFile} accumulated, effectively propagating historical counts.
     *
     * @param currentFile  the class file to update
     * @param previousFile the same class file in the preceding release, or {@code null}
     *                     if the file did not exist yet
     */
    private void copyFileRevisions(ClassFile currentFile, ClassFile previousFile) {
        if (previousFile != null) {
            for (int j = 0; j < previousFile.getNR(); j++) {
                currentFile.incrementNR();
            }
        }
    }

    /**
     * Processes all commits belonging to {@code currentRelease}, updating NR,
     * creation dates, and nAuth for each class file touched.  Also sorts the commits
     * chronologically before iterating.
     *
     * @param releaseIndex   0-based index of the release (used to look up previous release)
     * @param currentRelease the release whose commits are being replayed
     * @param veryFirstCommit the very first commit seen across all releases (may be {@code null}
     *                        if this is the first release processed)
     */
    private void processCommitsForRelease(int releaseIndex, Release currentRelease, RevCommit veryFirstCommit) {
        List<RevCommit> revCommitList = currentRelease.getAllReleaseCommits();
        RevCommit firstCommit = revCommitList.get(0);
        sortCommits(revCommitList);
        processCommits(releaseIndex, currentRelease, revCommitList, veryFirstCommit);
        creationDateSetter(currentRelease.getReleaseAllClass(), firstCommit);
    }

    /**
     * Iterates over a sorted list of commits, updating the {@code veryFirstCommit}
     * reference on the first iteration and delegating per-commit changes to
     * {@link #processCommitChanges}.
     *
     * @param releaseIndex    0-based index of the release
     * @param currentRelease  release context
     * @param commits         chronologically sorted list of commits for this release
     * @param veryFirstCommit overall first commit seen; may be {@code null}
     */
    private void processCommits(int releaseIndex, Release currentRelease, List<RevCommit> commits, RevCommit veryFirstCommit) {
        for (RevCommit commit : commits) {
            if (veryFirstCommit == null) {
                veryFirstCommit = commit;
            }
            processCommitChanges(releaseIndex, currentRelease, commit);
        }
    }

    /**
     * Extracts the list of modified and added {@code .java} files from a single commit
     * and delegates metric updates to {@link #updateChangesForRelease} (NR + creation date)
     * and {@link #updateNAuth} (unique authors).
     *
     * @param releaseIndex   0-based index of the current release
     * @param currentRelease release being processed
     * @param commit         the commit to examine
     */
    private void processCommitChanges(int releaseIndex, Release currentRelease, RevCommit commit) {
        List<String> modifiedFiles = gitHubInfoRetrieve.getDifference(commit, false);
        List<String> addedFiles = gitHubInfoRetrieve.getDifference(commit, true);
        Date commitDate = Date.from(commit.getCommitterIdent().getWhenAsInstant());
        String authorName = commit.getAuthorIdent().getName();
        updateChangesForRelease(releaseIndex, currentRelease, modifiedFiles, addedFiles, commitDate);
        updateNAuth(modifiedFiles, currentRelease, authorName);
    }

    /**
     * Updates NR and creation dates for the given commit's modified/added files,
     * but only when at least one file was modified (pure additions do not bump NR).
     *
     * @param releaseIndex   0-based index used to look up the previous release
     * @param currentRelease the release being updated
     * @param modifiedFiles  paths of {@code .java} files modified in this commit
     * @param addedFiles     paths of {@code .java} files added in this commit
     * @param commitDate     timestamp of the commit
     */
    private void updateChangesForRelease(int releaseIndex, Release currentRelease,
                                         List<String> modifiedFiles, List<String> addedFiles, Date commitDate) {
        if (!modifiedFiles.isEmpty()) {
            updateNr(modifiedFiles, currentRelease);
            Release previousRelease = (releaseIndex > 0) ? releaseList.get(releaseIndex - 1) : currentRelease;
            calculateDateOfCreation(currentRelease, previousRelease, commitDate, addedFiles);
        }
    }

    /**
     * Computes the age (in days) of every class file across all releases.
     *
     * <p>For the first release, age is the number of days between the file's
     * creation date and the release date.  For subsequent releases, age is
     * accumulated: {@code age = previousAge + daysSincePreviousCreation}.
     *
     * <p>If a file does not exist in the previous release (newly introduced),
     * the age is computed relative to the current release date as a fallback.
     *
     * @param releaseList ordered list of releases whose class files need age computation
     */
    private void calculateAge(List<Release> releaseList) {
        int len = releaseList.size();
        for (int i = 0; i < len; i++) {
            Release currRelease = releaseList.get(i);
            List<ClassFile> allReleaseFiles = currRelease.getReleaseAllClass();
            if (i == 0) {
                for (ClassFile file : allReleaseFiles) {
                    int age = (int) ((currRelease.getDate().getTime() - file.getCreationDate().getTime()) / 86400000);
                    file.setAge(age);
                }
                continue;
            }
            Release precRelease = releaseList.get(i - 1);
            for (ClassFile file : allReleaseFiles) {
                ClassFile preFile;
                try {
                    preFile = precRelease.getClassFileByKey(ClassFile.getKey(file));
                    int age = (int) ((file.getCreationDate().getTime() - preFile.getCreationDate().getTime()) / 86400000);
                    age = age + preFile.getAge();
                    if (age < preFile.getAge()) {
                        age = preFile.getAge();
                    }
                    file.setAge(age);
                } catch (Exception e) {
                    int age = (int) ((currRelease.getDate().getTime() - file.getCreationDate().getTime()) / 86400000);
                    file.setAge(age);
                }
            }
        }
    }

    /**
     * Sets the creation date of any class file that still has {@code null} as its
     * creation date, using the timestamp of the first commit in the release as a
     * conservative fallback.
     *
     * @param classFiles  list of class files to patch
     * @param firstCommit the earliest commit in the release
     */
    private void creationDateSetter(List<ClassFile> classFiles, RevCommit firstCommit) {
        for (ClassFile file : classFiles) {
            if (file.getCreationDate() == null) {
                file.setCreationDate(Date.from(firstCommit.getCommitterIdent().getWhenAsInstant()));
            }
        }
    }

    /**
     * Records a unique author name for each class file touched by a commit,
     * incrementing the nAuth (number of unique authors) metric.
     *
     * @param modifiedFiles list of file paths modified in the commit
     * @param release       release context used to look up {@link ClassFile} objects
     * @param authName      git author name extracted from the commit
     */
    private void updateNAuth(List<String> modifiedFiles, Release release, String authName) {
        for (String path : modifiedFiles) {
            List<ClassFile> classFiles = release.getClassFileByPath(path);
            for (ClassFile file : classFiles) {
                if (file != null) {
                    file.addAuthor(authName);
                }
            }
        }
    }

    /**
     * Increments the NR (number of revisions) counter for each class file that
     * appears in the list of modified files.
     *
     * @param modifiedFiles list of file paths modified in the commit
     * @param release       release context used to look up {@link ClassFile} objects
     */
    private void updateNr(List<String> modifiedFiles, Release release) {
        for (String path : modifiedFiles) {
            List<ClassFile> classFiles = release.getClassFileByPath(path);
            for (ClassFile file : classFiles) {
                if (file != null) {
                    file.incrementNR();
                }
            }
        }
    }

    /**
     * Sets the earliest known creation date for class files that were added in a
     * given commit.
     *
     * <p>When the current and previous release are the same (first release), the
     * creation date is simply the earliest commit date seen for that file.
     * Otherwise, {@link #parserFiles} is used to decide whether the file is truly
     * new (not present in the previous release) or was already tracked.
     *
     * @param currentRelease  the release being processed
     * @param precRelease     the immediately preceding release
     * @param commitDate      timestamp of the commit that added the files
     * @param addedFiles      list of {@code .java} file paths added in the commit
     */
    private void calculateDateOfCreation(Release currentRelease, Release precRelease, Date commitDate, List<String> addedFiles) {
        if (currentRelease.getId() == precRelease.getId()) {
            for (String file : addedFiles) {
                List<ClassFile> currFiles = currentRelease.getClassFileByPath(file);
                for (ClassFile currFile : currFiles) {
                    if (currFile != null && (currFile.getCreationDate() == null || currFile.getCreationDate().after(commitDate))) {
                        currFile.setCreationDate(commitDate);
                    }
                }
            }
            return;
        }
        parserFiles(addedFiles, precRelease, currentRelease, commitDate);
    }

    /**
     * Processes a list of added files, routing each one to either
     * {@link #updateNewClassFiles} (file did not exist in previous release) or
     * {@link #updateExistingClassFiles} (file already tracked in previous release).
     *
     * @param addedFiles     paths of added files
     * @param precRelease    previous release used to check prior existence
     * @param currentRelease release whose class files will be updated
     * @param commitDate     timestamp to set as the creation date
     */
    private void parserFiles(List<String> addedFiles, Release precRelease, Release currentRelease, Date commitDate) {
        addedFiles.forEach(file -> processFile(file, precRelease, currentRelease, commitDate));
    }

    /**
     * Determines whether a file is brand-new (not present in {@code precRelease})
     * and delegates creation-date assignment accordingly.
     *
     * @param file           file path to evaluate
     * @param precRelease    previous release
     * @param currentRelease current release
     * @param commitDate     commit timestamp to use as creation date
     */
    private void processFile(String file, Release precRelease, Release currentRelease, Date commitDate) {
        List<ClassFile> precFiles = precRelease.getClassFileByPath(file);
        List<ClassFile> currFiles = currentRelease.getClassFileByPath(file);
        if (precFiles.isEmpty()) {
            updateNewClassFiles(currFiles, commitDate);
        } else {
            updateExistingClassFiles(currFiles, commitDate);
        }
    }

    /**
     * Sets the creation date for files that are truly new (not in the previous
     * release), keeping the earliest date seen across multiple commits.
     *
     * @param currFiles  class files in the current release
     * @param commitDate candidate creation date
     */
    private void updateNewClassFiles(List<ClassFile> currFiles, Date commitDate) {
        currFiles.stream()
                .filter(Objects::nonNull)
                .forEach(currFile -> updateFileCreationDate(currFile, commitDate));
    }

    /**
     * Overwrites the creation date for files already tracked in the previous release,
     * using the commit date (the file was touched again, so we treat this as a "re-add").
     *
     * @param currFiles  class files in the current release
     * @param commitDate date to set
     */
    private void updateExistingClassFiles(List<ClassFile> currFiles, Date commitDate) {
        currFiles.stream()
                .filter(Objects::nonNull)
                .forEach(currFile -> currFile.setCreationDate(commitDate));
    }

    /**
     * Updates a single class file's creation date only if no date has been set yet
     * or the new date is earlier than the existing one.
     *
     * @param currFile   the class file to update
     * @param commitDate the candidate date
     */
    private void updateFileCreationDate(ClassFile currFile, Date commitDate) {
        if (currFile.getCreationDate() == null || commitDate.before(currFile.getCreationDate())) {
            currFile.setCreationDate(commitDate);
        }
    }

    // -------------------------------------------------------------------------
    // Commit–release mapping
    // -------------------------------------------------------------------------

    /**
     * Builds a map from every commit that precedes {@code targetRelease} to the
     * release it logically belongs to (i.e., the earliest release whose date is
     * strictly after the commit's author timestamp).
     *
     * <p>Commits that fall after the last preceding release are mapped to
     * {@code targetRelease} itself as a fallback.
     *
     * @param targetRelease the release currently being processed; only commits from
     *                      releases with a lower id are included
     * @return a map of {@link RevCommit} → {@link Release}
     */
    Map<RevCommit, Release> filterCommitsByRelease(Release targetRelease) {
        Map<RevCommit, Release> commitReleaseMap = new HashMap<>();

        List<Release> relevantReleases = releaseList.stream()
                .filter(r -> r.getId() < targetRelease.getId())
                .sorted(Comparator.comparing(Release::getDate))
                .toList();

        for (Release currentRelease : relevantReleases) {
            for (RevCommit commit : currentRelease.getAllReleaseCommits()) {
                Release appropriateRelease = relevantReleases.stream()
                        .filter(r -> r.getDate().toInstant().isAfter(commit.getCommitterIdent().getWhenAsInstant()))
                        .findFirst()
                        .orElse(targetRelease);
                commitReleaseMap.put(commit, appropriateRelease);
            }
        }

        Map<Release, Integer> releaseCount = new HashMap<>();
        for (Release release : commitReleaseMap.values()) {
            releaseCount.merge(release, 1, Integer::sum);
        }
        LOGGER.debug("after filtering commit for the release {} ", targetRelease.getName());
        releaseCount.forEach((release, count) ->
                LOGGER.debug("found commits {} for the release {} ", count, release.getName()));

        return commitReleaseMap;
    }

    // -------------------------------------------------------------------------
    // CK metric computation
    // -------------------------------------------------------------------------

    /**
     * Runs the CK tool on the source tree checked out under {@code sourcePath}
     * for the given {@code commit}, collecting metrics only for methods that were
     * actually changed in that commit (as reported by {@link #fillMethodsBuggy}).
     *
     * @param commit     the commit whose checked-out sources will be analysed
     * @param sourcePath path to the directory containing the checked-out {@code .java} files
     * @param release    the release the commit belongs to (used to look up {@link ClassFile} objects)
     * @return a map from method key to fully populated {@link MethodInstance}
     */
    private Map<String, MethodInstance> calculateCKMetrics(RevCommit commit, Path sourcePath, Release release) {
        Map<String, MethodInstance> methodInstanceResults = new HashMap<>();
        List<MethodInstance> methodsChanged = fillMethodsBuggy(commit);
        CK ck = new CK();
        ck.calculate(sourcePath, classResult -> processClassResult(
                classResult, release, sourcePath, methodsChanged, methodInstanceResults));
        return methodInstanceResults;
    }

    /**
     * Computes CK metrics for the refactored version of the target method defined
     * by the project configuration.  Results are memoised via
     * {@link Projects#isRefactoredMethodsFilled()} so the CK tool is only run once.
     *
     * @return map from method key to fully populated {@link MethodInstance} for the
     *         refactored methods
     */
    private Map<String, MethodInstance> processRefactoredMethods() {
        Map<String, MethodInstance> methodInstanceResults = new HashMap<>();
        if (project.isRefactoredMethodsFilled()) {
            return project.getFilledRefactoredMethods();
        }
        List<MethodInstance> methodsChanged = project.getInitializedRefactoredMethods();
        Path sourcePath = project.getRefactoredSourcePath();
        Release release = project.getRefactoredRelease(releaseList);
        CK ck = new CK();
        ck.calculate(sourcePath, classResult -> processClassResult(
                classResult, release, sourcePath, methodsChanged, methodInstanceResults));
        project.setMethods(methodInstanceResults);
        return methodInstanceResults;
    }

    /**
     * CK callback invoked once per class found in the checked-out source tree.
     * For each method in the class it checks whether the method was changed in the
     * current commit ({@link #isMethodChanged}); if so, it collects code-smell
     * metrics via {@link PmdRunner} and constructs a {@link MethodInstance} via
     * {@link #processMethod}.
     *
     * @param classResult         CK result for a single class
     * @param release             release the commit belongs to
     * @param sourcePath          root of the checked-out source tree (used by PMD)
     * @param changedMethod       methods changed in the current commit
     * @param methodInstanceResults accumulator populated by this callback
     */
    private void processClassResult(CKClassResult classResult, Release release, Path sourcePath,
                                    List<MethodInstance> changedMethod, Map<String, MethodInstance> methodInstanceResults) {
        if (classResult.getMethods() == null || classResult.getMethods().isEmpty()) {
            return;
        }
        ClassFile filledClass = release.findClassFileByApproxName(classResult.getClassName());
        if (filledClass == null) {
            return;
        }
        classResult.getMethods().forEach(method -> {
            MethodInstance methodInstance = isMethodChanged(method, filledClass, changedMethod);
            if (methodInstance != null) {
                int nSmell = PmdRunner.collectCodeSmellMetricsClass(classResult.getClassName(), sourcePath.toString(),
                        method.getStartLine(), method.getStartLine() + method.getLoc());
                processMethod(method, filledClass, methodInstance, release, methodInstanceResults, nSmell);
            }
        });
    }

    /**
     * Determines whether a CK method result corresponds to one of the methods that
     * was changed in the current commit by matching on method name and containing
     * class path.
     *
     * @param method        CK result for a single method
     * @param filledClass   the {@link ClassFile} the method belongs to
     * @param methodChanged list of changed methods from {@link GitHubInfoRetrieve#getChangedMethodInstances}
     * @return the matching {@link MethodInstance} if found, {@code null} otherwise
     */
    private MethodInstance isMethodChanged(CKMethodResult method, ClassFile filledClass, List<MethodInstance> methodChanged) {
        String methodName = MethodInstance.cleanMethodName(method.getMethodName());
        for (MethodInstance methodInstance : methodChanged) {
            if (methodName.equals(methodInstance.getMethodName())
                    && filledClass.getPath().contains(methodInstance.getClassPath())) {
                return methodInstance;
            }
        }
        return null;
    }

    /**
     * Creates a fully populated {@link MethodInstance} from a CK method result and
     * inserts it into {@code methodInstanceResults}.
     *
     * @param method                CK metrics for the method
     * @param filledClass           class file the method belongs to
     * @param filledMethod          the matched changed-method descriptor
     * @param release               release context
     * @param methodInstanceResults output accumulator
     * @param nSmell                number of code smells detected by PMD for this method
     */
    private void processMethod(CKMethodResult method, ClassFile filledClass, MethodInstance filledMethod,
                               Release release, Map<String, MethodInstance> methodInstanceResults, int nSmell) {
        try {
            MethodInstance methodInstance = createMethodInstance(method, filledClass, filledMethod, release, nSmell);
            methodInstanceResults.put(MethodInstance.createMethodKey(methodInstance), methodInstance);
        } catch (Exception e) {
            LOGGER.error("Error during method analysis: {} - {}",
                    method.getQualifiedMethodName(), e.getMessage(), e);
        }
    }

    /**
     * Allocates a new {@link MethodInstance}, populates its identity fields
     * (class path, class name, method name, release name) and delegates metric
     * assignment to {@link #setMethodMetrics}.
     *
     * @param method       CK method result
     * @param filledClass  containing class file
     * @param filledMethod matched changed-method descriptor
     * @param release      release context
     * @param nSmell       PMD smell count
     * @return a fully initialised {@link MethodInstance}
     */
    private MethodInstance createMethodInstance(CKMethodResult method, ClassFile filledClass,
                                                MethodInstance filledMethod, Release release, int nSmell) {
        MethodInstance methodInstance = new MethodInstance();
        methodInstance.setClassPath(filledClass.getPath());
        methodInstance.setClassName(filledClass.getClassName());
        methodInstance.setMethodName(filledMethod.getMethodName());
        methodInstance.setReleaseName(release.getName());
        setMethodMetrics(methodInstance, method, filledClass, nSmell);
        filledClass.addMethod(methodInstance);
        return methodInstance;
    }

    /**
     * Transfers CK and class-level metrics from the raw CK result objects into the
     * {@link MethodInstance} model.  Buggyness is initialised to {@code false};
     * it is set to {@code true} later by {@link #assignBuggyness}.
     *
     * <p>Metrics set here:
     * <ul>
     *   <li>LOC, WMC, assignments, math operations, try-catch blocks, return statements</li>
     *   <li>Fan-in, fan-out</li>
     *   <li>Number of code smells (PMD)</li>
     *   <li>Age, nAuth, NR (from the containing {@link ClassFile})</li>
     * </ul>
     *
     * @param methodInstance target to populate
     * @param method         CK method result
     * @param filledClass    class file providing class-level metrics
     * @param nSmell         PMD smell count for this method's line range
     */
    private void setMethodMetrics(MethodInstance methodInstance, CKMethodResult method, ClassFile filledClass, int nSmell) {
        methodInstance.setLoc(method.getLoc());
        methodInstance.setWmc(method.getWmc());
        methodInstance.setQtyAssigment(method.getAssignmentsQty());
        methodInstance.setQtyMathOperations(method.getMathOperationsQty());
        methodInstance.setQtyTryCatch(method.getTryCatchQty());
        methodInstance.setQtyReturn(method.getReturnQty());
        methodInstance.setFanin(method.getFanin());
        methodInstance.setFanout(method.getFanout());
        methodInstance.setnSmells(nSmell);
        methodInstance.setAge(filledClass.getAge());
        methodInstance.setnAuth(filledClass.getnAuth());
        methodInstance.setNr(filledClass.getNR());
        methodInstance.setBuggy(false);
    }

    // -------------------------------------------------------------------------
    // Buggyness assignment
    // -------------------------------------------------------------------------

    /**
     * Cache of changed-method lists keyed by commit, computed lazily on first access.
     * Using {@link ConcurrentHashMap} ensures thread-safe access during parallel
     * commit processing.
     */
    Map<RevCommit, List<MethodInstance>> changedMethods = new ConcurrentHashMap<>();

    /**
     * Returns (and caches) the list of methods changed or deleted in {@code commit},
     * as reported by {@link GitHubInfoRetrieve#getChangedMethodInstances}.
     *
     * @param commit the commit to inspect
     * @return list of {@link MethodInstance} descriptors for changed/deleted methods
     */
    List<MethodInstance> fillMethodsBuggy(RevCommit commit) {
        return changedMethods.computeIfAbsent(commit, gitHubInfoRetrieve::getChangedMethodInstances);
    }

    /**
     * Resolves conflicts in {@code releaseData.releaseResults} where the same method
     * key appears from multiple commits: keeps the entry with the highest age value,
     * since a higher age indicates a more recent snapshot and therefore more accurate
     * cumulative metrics.
     *
     * @param releaseData the release context whose results should be reordered
     */
    private void reorderReleaseResults(ReleaseData releaseData) {
        String methodKey;
        for (String commitHash : releaseData.commitsByHash.keySet()) {
            Map<String, MethodInstance> methods = resultCommitsMethods.get(commitHash);
            if (methods == null) {
                continue;
            }
            for (MethodInstance method : methods.values()) {
                methodKey = MethodInstance.createMethodKey(method);
                if (releaseData.releaseResults.containsKey(methodKey)
                        && releaseData.releaseResults.get(methodKey).getAge() < method.getAge()) {
                    releaseData.releaseResults.put(methodKey, method);
                }
            }
        }
    }

    /**
     * Labels each method in {@code data.releaseResults} as buggy or clean based on
     * the IV–FV range of the associated tickets.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Calls {@link #reorderReleaseResults} to ensure the most up-to-date
     *       snapshot of each method is used.</li>
     *   <li>Injects the refactored-method metrics if the current release is past
     *       the refactored release defined in the project configuration.</li>
     *   <li>Resets all buggyness flags to {@code false}.</li>
     *   <li>Builds a release-indexed lookup of methods by signature.</li>
     *   <li>For every ticket with a known IV, calls {@link #processTicketChanges}
     *       to mark methods in the [IV, FV) range as buggy.</li>
     * </ol>
     *
     * <p>This method is a no-op if {@link #resultsChanged} is {@code false}.
     *
     * @param data the release context containing tickets and accumulated method results
     */
    private void    assignBuggyness(ReleaseData data) {
        reorderReleaseResults(data);
        List<Ticket> refactoredTickets = new ArrayList<>();
        if (project.afterRefactoredRelease(data.release, releaseList)) {
            Map<String, MethodInstance> refactoredMethods = processRefactoredMethods();
            data.releaseResults.putAll(refactoredMethods);
        }
        if (!resultsChanged) return;
        resultsChanged = false;
        LOGGER.info("Initializing buggyness assignment");
        data.releaseResults.values().forEach(method -> method.setBuggy(false));
        if (data.releaseTickets == null || data.releaseTickets.isEmpty()) {
            LOGGER.info("No tickets found for this release");
            return;
        }

        Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease = new HashMap<>();
        data.releaseResults.values().forEach(method -> {
            if (method.getReleaseName() != null) {
                int releaseId = Release.getId(method.getReleaseName(), releaseList);
                String methodKey = method.getClassPath() + "#" + method.getClassName() + "#" + method.getMethodName();
                methodsByRelease
                        .computeIfAbsent(releaseId, k -> new HashMap<>())
                        .computeIfAbsent(methodKey, k -> new ArrayList<>())
                        .add(method);
            }
        });

        for (Ticket ticket : data.releaseTickets) {
            Release checkInj = ticket.getIv() != null ? ticket.getIv() : ticket.getCalculatedIv();
            if (checkInj == null) {
                continue;
            }
            processTicketChanges(ticket, methodsByRelease, refactoredTickets, data.commitsByHash);
        }
        project.setTickets(data.release, refactoredTickets);
        LOGGER.info("Buggyness assignment completed");
    }

    /**
     * Processes all commits associated with a single ticket and marks the methods
     * they touched as buggy in every release between the ticket's IV (inclusive)
     * and FV (exclusive).
     *
     * @param ticket             the bug-fixing ticket
     * @param methodsByRelease   release-indexed method lookup built in {@link #assignBuggyness}
     * @param refactoredTickets  accumulator for tickets touching the target refactored method
     * @param commitsByHash      map from commit SHA-1 to {@link RevCommit}
     */
    private void processTicketChanges(Ticket ticket,
                                      Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease,
                                      List<Ticket> refactoredTickets,
                                      Map<String, RevCommit> commitsByHash) {
        Release injected = ticket.getIv() != null ? ticket.getIv() : ticket.getCalculatedIv();
        Release fixed = ticket.getFv();

        getSortedCommit(ticket.getAssociatedCommits()).stream()
                .map(commit -> processCommitMethods(commit, commitsByHash))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(methodData -> {
                    updateRefactoredTickets(ticket, methodData.changedMethods, refactoredTickets);
                    updateBuggyness(methodsByRelease, methodData.modifiedSignatures, injected.getId(), fixed.getId());
                });
    }

    /**
     * Validates a commit for buggyness processing and, if valid, extracts the set
     * of modified method signatures.
     *
     * <p>A commit is considered valid only when:
     * <ul>
     *   <li>Its CK metrics are available in {@code resultCommitsMethods}.</li>
     *   <li>At least one method was changed in the commit.</li>
     *   <li>The commit object is present in {@code commitsByHash}.</li>
     * </ul>
     *
     * @param commit        commit to evaluate
     * @param commitsByHash map from SHA-1 to {@link RevCommit}
     * @return an {@link Optional} containing a {@link MethodData} with changed methods
     *         and their signatures, or empty if the commit is invalid
     */
    private Optional<MethodData> processCommitMethods(RevCommit commit, Map<String, RevCommit> commitsByHash) {
        String commitHash = commit.getId().getName();
        List<MethodInstance> methodsChanged = fillMethodsBuggy(commit);
        Map<String, MethodInstance> commitMethods = resultCommitsMethods.get(commitHash);

        if (!isValidCommit(commitMethods, methodsChanged, commitsByHash.get(commitHash))) {
            return Optional.empty();
        }
        Set<String> modifiedSignatures = extractModifiedSignatures(methodsChanged);
        return Optional.of(new MethodData(methodsChanged, modifiedSignatures));
    }

    /**
     * Returns {@code true} when the commit has CK metric data, at least one changed
     * method, and a non-null entry in the commit map.
     *
     * @param commitMethods  CK results for the commit (may be {@code null})
     * @param methodsChanged list of methods changed in the commit
     * @param commit         the commit object (may be {@code null} if not in scope)
     * @return {@code true} if all validity conditions are met
     */
    private boolean isValidCommit(Map<String, MethodInstance> commitMethods,
                                  List<MethodInstance> methodsChanged,
                                  RevCommit commit) {
        return commitMethods != null && !methodsChanged.isEmpty() && commit != null;
    }

    /**
     * Converts a list of changed {@link MethodInstance}s into a set of signature
     * strings of the form {@code classPath#className#methodName}, used as lookup
     * keys when marking buggyness.
     *
     * @param methodsChanged changed methods to convert
     * @return set of signature strings
     */
    private Set<String> extractModifiedSignatures(List<MethodInstance> methodsChanged) {
        Set<String> signatures = new HashSet<>();
        for (MethodInstance changedMethod : methodsChanged) {
            signatures.add(changedMethod.getClassPath() + "#" + changedMethod.getClassName() + "#" + changedMethod.getMethodName());
        }
        return signatures;
    }

    /**
     * Adds {@code ticket} to {@code refactoredTickets} if any of its changed methods
     * matches the project's target method for the what-if refactoring analysis.
     *
     * @param ticket            the ticket being evaluated
     * @param changedMethods    methods changed by commits associated with the ticket
     * @param refactoredTickets accumulator for tickets that touch the refactored method
     */
    private void updateRefactoredTickets(Ticket ticket,
                                         List<MethodInstance> changedMethods,
                                         List<Ticket> refactoredTickets) {
        boolean shouldAdd = changedMethods.stream().anyMatch(this::isMethodToRefactor);
        if (shouldAdd && !refactoredTickets.contains(ticket)) {
            refactoredTickets.add(ticket);
        }
    }

    /**
     * Returns {@code true} if {@code method} is the project's designated target
     * for the what-if refactoring analysis, matching on both method name and class path.
     *
     * @param method the method to test
     * @return {@code true} if the method matches the refactoring target
     */
    private boolean isMethodToRefactor(MethodInstance method) {
        return project.getMethodToRefactor().getMethodName().contains(method.getMethodName()) &&
                method.getClassPath().contains(project.getMethodToRefactor().getClassPath());
    }

    /**
     * Immutable value object coupling the list of changed methods with the set of
     * their extracted signatures, avoiding repeated computation inside stream pipelines.
     */
    private static class MethodData {
        final List<MethodInstance> changedMethods;
        final Set<String> modifiedSignatures;

        MethodData(List<MethodInstance> changedMethods, Set<String> modifiedSignatures) {
            this.changedMethods = changedMethods;
            this.modifiedSignatures = modifiedSignatures;
        }
    }

    /**
     * Returns the commit list sorted in ascending chronological order (oldest first).
     *
     * @param associatedCommits list to sort in place
     * @return the same list, now sorted
     */
    private List<RevCommit> getSortedCommit(List<RevCommit> associatedCommits) {
        sortCommits(associatedCommits);
        return associatedCommits;
    }

    /**
     * Iterates over every release in the range [{@code injectedId}, {@code fixedId})
     * and marks as buggy any method whose signature appears in {@code modifiedMethodSignatures}.
     *
     * <p>Releases where no method map exists in {@code methodsByRelease} are silently
     * skipped.  A WARN is emitted (and the method returns early) if
     * {@code injectedId >= fixedId}, which would indicate an inconsistent ticket.
     *
     * @param methodsByRelease        release-indexed method lookup
     * @param modifiedMethodSignatures set of {@code classPath#className#methodName} strings
     * @param injectedId              id of the injected-version release (inclusive)
     * @param fixedId                 id of the fixed-version release (exclusive)
     */
    private void updateBuggyness(Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease,
                                 Set<String> modifiedMethodSignatures,
                                 int injectedId,
                                 int fixedId) {
        if (injectedId >= fixedId) {
            LOGGER.warn("Invalid release range: injectedId {} >= fixedId {}", injectedId, fixedId);
            return;
        }
        for (int releaseId = injectedId; releaseId < fixedId; releaseId++) {
            Map<String, List<MethodInstance>> releaseMethods = methodsByRelease.get(releaseId);
            if (releaseMethods == null) continue;
            markBuggyMethods(releaseMethods, modifiedMethodSignatures, releaseId);
        }
    }

    private void markBuggyMethods(Map<String, List<MethodInstance>> releaseMethods,
                                   Set<String> modifiedMethodSignatures,
                                   int releaseId) {
        for (String methodSignature : modifiedMethodSignatures) {
            List<MethodInstance> methods = releaseMethods.get(methodSignature);
            if (methods == null || methods.isEmpty()) continue;
            for (MethodInstance method : methods) {
                if (!method.isBuggy()) {
                    method.setBuggy(true);
                    LOGGER.debug("Marked method as buggy: {} in release {}", methodSignature, releaseId);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Commit ordering
    // -------------------------------------------------------------------------

    /**
     * Sorts {@code commits} in ascending chronological order (oldest commit first)
     * using {@link RevCommitComparator}.
     *
     * @param commits the list to sort in place
     */
    private void sortCommits(List<RevCommit> commits) {
        Collections.sort(commits, new RevCommitComparator());
    }

    /**
     * Compares two {@link RevCommit}s by their committer timestamp, enabling
     * ascending chronological ordering.
     */
    private class RevCommitComparator implements Comparator<RevCommit> {
        @Override
        public int compare(RevCommit a, RevCommit b) {
            return a.getCommitterIdent().getWhenAsInstant().compareTo(b.getCommitterIdent().getWhenAsInstant());
        }
    }
}
