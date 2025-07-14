
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

public class MetricsCalculator {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsCalculator.class);
    private final Path tempDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "ck_analysis");
    private List<Release> releaseList;
    private final GitHubInfoRetrieve gitHubInfoRetrieve;
    private final String projectName;
    private final RepositoryManager repositoryManager;
    private boolean resultsChanged;
    private final Map <String,Map<String,MethodInstance>> resultCommitsMethods=new ConcurrentHashMap<>();
    private final Projects project;

    /**
     * Constructor that takes a GitHubInfoRetrieve object and a project name
     */
    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve, String projectName, Projects project) throws IOException {
        this.gitHubInfoRetrieve = gitHubInfoRetrieve;
        this.projectName = projectName;
        Files.createDirectories(Paths.get(String.valueOf(tempDirPath)));
        Path cacheDirPath = ConstantsWindowsFormat.CACHE_PATH;
        Files.createDirectories(cacheDirPath);

        // Initialize the repository manager
        this.repositoryManager = new RepositoryManager(gitHubInfoRetrieve);
        this.resultsChanged=false;
        this.project=project;
        // Load the commit cache using the optimized method
        Caching.loadCommitCache(resultCommitsMethods, null,projectName);
    }
    /**
     * Data class to hold release processing information
     */
    public static class ReleaseData {
        Release release;
        ConcurrentMap<String, MethodInstance> releaseResults;
        Map<RevCommit, Release> mapCommitRelease;
        List<RevCommit> releaseCommits;
        Map<String, RevCommit> commitsAnalyzed;
        Set<String> commitHashesToProcess;
        Map<String, RevCommit> commitsByHash;
        List<Ticket> releaseTickets;
        DataSetType dataSetType;
    }
    Map <Release,Set<CommitCheck>> referenceMap=new HashMap<>();
    private static class CommitCheck {
        private final RevCommit commit;
        private Set<MethodInstance> methods;

        public CommitCheck(RevCommit commit) {
            this.commit = commit;
            this.methods = null;
        }

        public void addMethods(Collection<MethodInstance> methods) {
            if(this.methods==null)this.methods=new HashSet<>();
            this.methods.addAll(methods);
        }

    }
    void populateReferenceMap(Release curRelease){
        List<Release> relevantReleases = releaseList.stream()
                .filter(r -> r.getId() < curRelease.getId())
                .toList();

        // Populate the reference map for releases that have not yet been processed
        for (Release release : relevantReleases) {
            if (!referenceMap.containsKey(release)) {
                Set<CommitCheck> releaseCommits = new HashSet<>();
                for (RevCommit commit : release.getAllReleaseCommits()) {
                    String commitHash = commit.getId().getName();
                    if (resultCommitsMethods.containsKey(commitHash)) {     // commits loaded from cache
                        CommitCheck commitCheck = new CommitCheck(commit);
                        commitCheck.addMethods(resultCommitsMethods.get(commitHash).values());
                        releaseCommits.add(commitCheck);
                    }else{
                        CommitCheck commitCheck = new CommitCheck(commit);  // commits not in cache
                        releaseCommits.add(commitCheck);
                    }

                }
                referenceMap.put(release, releaseCommits);
            }
        }
    }
    void logReleaseResults(ReleaseData releaseData){
        LOGGER.info(" after the association commit in cache ");
        for(Release release :releaseList){
            int count=0;
            for(MethodInstance method: releaseData.releaseResults.values()){
                if(method.getReleaseName().equals(release.getName())){
                    count++;
                }
            }
            LOGGER.debug("found methods {} for the release {} ", count, release.getName());
        }
    }

    void getCommitsInCache(ReleaseData releaseData ) {
        populateReferenceMap(releaseData.release);
        List<Release> relevantReleases = releaseList.stream()
                .filter(r -> r.getId() < releaseData.release.getId())
                .toList();

        LOGGER.info(" after the association commit in cache ");
        for(Release release: relevantReleases){
            for( CommitCheck commitCheck: referenceMap.get(release)){
                String commitHash = commitCheck.commit.getId().getName();
                releaseData.commitsByHash.put(commitHash, commitCheck.commit); // contains all the commits to process
                if(commitCheck.methods==null){                  // No commit from cache i have to process it
                    releaseData.commitHashesToProcess.add(commitHash);
                    continue;
                }
                Map<String,MethodInstance> commitMetrics=new HashMap<>();
                for( MethodInstance method: commitCheck.methods){
                    method.setReleaseName(release.getName());
                    String classKey=ClassFile.getKey(method.getClassPath(),method.getClassName());
                    ClassFile classFile=release.getClassFileByKey(classKey);
                    if(classFile!=null)classFile.addMethod(method);
                    commitMetrics.put(MethodInstance.createMethodKey(method),method);
                }
                releaseData.releaseResults.putAll(commitMetrics);
                releaseData.commitsAnalyzed.put(commitHash, commitCheck.commit);
                releaseData.releaseCommits.add(commitCheck.commit);
                resultsChanged=true;
            }
        }
        logReleaseResults(releaseData);
        resultsChanged=true;
    }

    void processCommits(ReleaseData releaseData) throws IOException, ExecutionException, InterruptedException {
        LOGGER.info("processing the other commits ");
        // Create a lock to synchronize repository access
        Object threadLock = new Object();

        // Separate the last commit for sequential processing
        AtomicInteger countThread = new AtomicInteger();
        // Limit the number of threads based on available memory and cores
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // Use fewer threads if memory is limited (< 2GB)
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int memoryBasedThreads = (int) Math.max(1, Math.min(maxMemory / 512, ConstantSize.NUM_THREADS));
        int numThreads = Math.min(availableProcessors, memoryBasedThreads);

        LOGGER.info("Maximum available memory: {} MB, Number of threads: {}", maxMemory, numThreads);

        // Split commits into smaller batches to reduce memory consumption
        List<String> commitHashList = new ArrayList<>(releaseData.commitHashesToProcess);
        int batchSize = Math.min(100, commitHashList.size()); // Process at most 100 commits at a time

        // Create the initial backup
        repositoryManager.backupRepository();

        // Current batch index
        int currentBatchIndex = 0;

        // Flag to indicate if processing needs to be restarted after a reset
        boolean restartProcessing=false;

        // Final thread pool that will also be used to process remaining classes in cache
        ForkJoinPool finalThreadPool = null;

        String finalCommitHash = commitHashList.get(commitHashList.size() - 1);

        // Process commits in batches with possibility of restart
        while (currentBatchIndex < commitHashList.size()) {
            // Reset the restart flag
            restartProcessing = false;

            // Create a new thread pool for each processing cycle
            // This allows completely restarting the processing after a reset
            ForkJoinPool customThreadPool=new ForkJoinPool();
            try {
                finalThreadPool = customThreadPool;
                // Calculate the end index for the current batch
                int endIndex = Math.min(currentBatchIndex + batchSize, commitHashList.size());
                List<String> batchCommits = commitHashList.subList(currentBatchIndex, endIndex);

                LOGGER.info("Processing commit batch {}/{} (size: {})",
                        (currentBatchIndex/batchSize) + 1,
                        (int) Math.ceil(commitHashList.size() / (double) batchSize),
                        batchCommits.size());
                try {
                    customThreadPool.submit(() ->
                            batchCommits.parallelStream().forEach(commitHash -> {
                                try {
                                    RevCommit commit = releaseData.commitsByHash.get(commitHash);
                                    Path commitTempDir = tempDirPath.resolve(releaseData.release.getName() + "_" + commitHash);
                                    releaseData.commitsAnalyzed.put(commitHash, commit);

                                    // Sincronizza l'accesso al repository Git
                                    synchronized (threadLock) {
                                        // Checkout del commit appartenente alla release
                                        repositoryManager.checkoutRelease(commit, commitTempDir , commitHash.equals(finalCommitHash));
                                    }

                                    countThread.getAndIncrement();
                                    Release curRelease = releaseData.mapCommitRelease.get(commit);
                                    if (curRelease == null) {
                                        curRelease=releaseData.release;
                                    }
                                    Map<String, MethodInstance> commitMetrics = calculateCKMetrics(commit, commitTempDir, curRelease);
                                    resultCommitsMethods.put(commitHash, commitMetrics);
                                    synchronized (threadLock) {
                                        // Aggiorna entrambe le mappe in modo atomico
                                        releaseData.releaseResults.putAll(commitMetrics);
                                    }
                                    synchronized (threadLock) {
                                        resultsChanged=true;
                                        outData(countThread.get(), releaseData);
                                    }

                                    // Pulisci la directory temporanea del commit
                                    repositoryManager.cleanupTempDirectory(commitTempDir);



                                } catch (OutOfMemoryError | Exception e) {
                                    LOGGER.error("Error during commit processing: {} , possible insufficient memory", commitHash, e);
                                }

                            })
                    ).get(); // Wait for completion
                } catch (Exception e) {
                    LOGGER.error("Error during batch processing: {}", e.getMessage(), e);
                    customThreadPool.shutdown();
                    // Execute complete reset
                    restartProcessing = handleProcessingError();
                }
                // Save intermediate state after each batch
                Caching.saveCommitCache(resultCommitsMethods, projectName);
                // Move to the next batch
                currentBatchIndex += batchSize;
            }finally{
                customThreadPool.shutdown();
            }
        }

        // If a restart was requested, recursively call this method
        if (restartProcessing) {
            LOGGER.info("Restarting processing after complete reset...");
            // Remove already processed commits from the list to process
            releaseData.commitHashesToProcess.removeAll(releaseData.commitsAnalyzed.keySet());
            // Recursively call the method to process remaining commits
            processCommits(releaseData);
            return;
        }

        // Normal completion of processing
        // Use the same thread pool to process remaining classes in cache
        if (finalThreadPool ==null) {
            // Fallback in case the thread pool was not created
            LOGGER.warn("Thread pool not available, sequential processing");
            Caching.saveCommitCache(resultCommitsMethods, projectName);
            repositoryManager.restoreFromBackup();

        }else {
            Caching.saveCommitCache(resultCommitsMethods, projectName);
            assignBuggyness(releaseData);
            ClassWriter.writeResultsToFile(releaseData.release, projectName, releaseData.releaseResults, releaseData.dataSetType);
        }
    }

    // Flag to indicate if a complete reset is in progress
    private volatile boolean resetInProgress = false;

    /**
     * Handles an error during processing, always performing a complete reset.
     * @return true if a complete reset was performed, false otherwise
     */
    private boolean handleProcessingError() {

        boolean resetPerformed = false;

        try {
            // Log memory information for debugging
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory() / (1024 * 1024);
            long freeMemory = runtime.freeMemory() / (1024 * 1024);
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory() / (1024 * 1024);

            LOGGER.info("Memory state during error: Used {}MB, Free {}MB, Total {}MB, Max {}MB",
                    usedMemory, freeMemory, totalMemory, maxMemory);

            if (!resetInProgress) {
                // Set the flag to avoid concurrent resets
                resetInProgress = true;

                LOGGER.warn("Error detected. Executing complete reset...");

                // Wait a moment to allow the system to stabilize
                Thread.sleep(10000);

                // Attempt to restore the backup
                gitHubInfoRetrieve.initializingRepo();
                // Create a new backup to start from a clean state
                LOGGER.info("Creating a new backup after reset...");
                repositoryManager.backupRepository();

                // Save the current state to not lose work done so far
                if (!resultCommitsMethods.isEmpty()) {
                    LOGGER.info("Saving current state after reset...");
                    Caching.saveCommitCache(resultCommitsMethods, projectName);
                }

                resetPerformed = true;

                // Reset completed
                resetInProgress = false;

                LOGGER.info("Complete reset executed successfully. Restarting processing...");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Operation interrupted during error handling", e);
        } catch (Exception restoreError) {
            LOGGER.error("Error during backup restoration: {}",
                    restoreError.getMessage(), restoreError);
        }

        return resetPerformed;
    }



    private void outData(int log, ReleaseData releaseData) {
        if ((log % ConstantSize.FREQUENCY_LOG) == 0) {
            int processedCommits = releaseData.commitsAnalyzed.size();
            // Count only the commits that are in commitHashesToProcess but not yet in commitsAnalyzed
            int remainingCommits = 0;
            for (String hash : releaseData.commitHashesToProcess) {
                if (!releaseData.commitsAnalyzed.containsKey(hash)) {
                    remainingCommits++;
                }
            }
            LOGGER.info("\n\n  Release {} Thread {} in progress... commits analyzed {}  commits to process {}) \n\n",
                    releaseData.release.getName(),
                    log,
                    processedCommits,
                    remainingCommits);
        }
        if ((log % ConstantSize.FREQUENCY_WRITE_CACHE) == 0) {
            Caching.saveCommitCache(resultCommitsMethods, projectName);
        }
        if ((log % ConstantSize.FREQUENCY_WRITE_CSV) == 0 ) {
            // Calculate buggyness for partial results
            assignBuggyness(releaseData);
            // Notify callback with partial results
            ClassWriter.writeResultsToFile(releaseData.release,projectName, releaseData.releaseResults,DataSetType.PARTIAL);
        }

    }


    public void calculateReleaseMetrics(Release release, List<Ticket> releaseTickets, DataSetType dataSetType) {
        // Utilizziamo ConcurrentHashMap per la thread-safety
        // it is an instance <key method method> because it continusely update methods during the commit
        // so when is present other method with same key upgrade the value in o(1) and not in o(n) if they will be in a set
        // the key is the signature of the metods

        ReleaseData data = new ReleaseData();
        data.release = release;
        data.releaseResults = new ConcurrentHashMap<>();
        data.releaseTickets = releaseTickets;
        data.dataSetType=dataSetType;
        LOGGER.info((" \n\n starting metrics calculation for release " + release.getName()));

        // Optimal number of threads based on available cores
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
                cachedCommitsSize,
                data.commitHashesToProcess.size());

        if (data.commitHashesToProcess.isEmpty()) {
            LOGGER.info("No commits to process for release ");
            assignBuggyness(data);
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, dataSetType);
            return;
        }
        if (!data.releaseResults.isEmpty() && data.commitHashesToProcess.size()>ConstantSize.FREQUENCY_WRITE_CSV ) {
            LOGGER.info("writing before the processing");
            assignBuggyness(data);
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, DataSetType.PARTIAL);
        }

        // Process only the commits that aren't in the cache
        int maxRetries = 3; // Maximum number of complete processing attempts
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // If it's not the first attempt, log information
                if (attempt > 0) {
                    LOGGER.info("Attempt {} of {} for processing release {}",
                            attempt + 1, maxRetries, release.getName());
                }

                processCommits(data);
                assignBuggyness(data);

                // If we get here, processing was completed successfully
                LOGGER.info("Processing of release {} completed successfully", release.getName());
                break;

            } catch (IOException | ExecutionException e) {
                LOGGER.error("Error during processing of release {}: {}",
                        release.getName(), e.getMessage(), e);

                // Handle the error and determine if a new attempt is needed
                boolean resetPerformed = handleProcessingError();

                if (!resetPerformed && attempt == maxRetries - 1  ) {
                    // Last attempt failed without reset, final error log
                    LOGGER.error("Unable to complete processing of release {} after {} attempts",
                            release.getName(), maxRetries);
                    Caching.saveCommitCache(resultCommitsMethods,projectName);
                    assignBuggyness(data);
                    ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, dataSetType);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                LOGGER.error("Processing interrupted for release {}", release.getName(), e);

                // Handle the interruption error
                handleProcessingError();

                // We don't retry in case of explicit interruption
                LOGGER.warn("Processing of release {} interrupted by user", release.getName());
            }
        }
    }



    public void calculateAll(List<Release> releaseList) {
        LOGGER.info("processing class metrics");
        this.releaseList = releaseList;

        processReleases(releaseList);

        calculateAge(releaseList);
        LOGGER.info("end of processing class metrics");
    }

    private void processReleases(List<Release> releaseList) {
        RevCommit veryFirstCommit = null;
        int len = releaseList.size();

        for(int i = 0; i < len; i++) {
            Release currRelease = releaseList.get(i);

            copyPreviousReleaseRevisions(i, currRelease, releaseList);
            processCommitsForRelease(i, currRelease, veryFirstCommit);
        }
    }

    private void copyPreviousReleaseRevisions(int currentIndex, Release currentRelease, List<Release> releaseList) {
        if (currentIndex > 0) {
            Release prevRelease = releaseList.get(currentIndex - 1);
            copyRevisions(currentRelease, prevRelease);
        }
    }

    private void copyRevisions(Release currentRelease, Release previousRelease) {
        for (ClassFile currFile : currentRelease.getReleaseAllClass()) {
            String classKey=ClassFile.getKey(currFile);
            ClassFile prevFile = previousRelease.getClassFileByKey(classKey);
            copyFileRevisions(currFile, prevFile);
        }
    }

    private void copyFileRevisions(ClassFile currentFile, ClassFile previousFile) {
        if (previousFile != null) {
            for (int j = 0; j < previousFile.getNR(); j++) {
                currentFile.incrementNR();
            }
        }
    }

    private void processCommitsForRelease(int releaseIndex, Release currentRelease, RevCommit veryFirstCommit) {
        List<RevCommit> revCommitList = currentRelease.getAllReleaseCommits();
        RevCommit firstCommit = revCommitList.get(0);
        sortCommits(revCommitList);

        processCommits(releaseIndex, currentRelease, revCommitList, veryFirstCommit);
        creationDateSetter(currentRelease.getReleaseAllClass(), firstCommit);
    }

    private void processCommits(int releaseIndex, Release currentRelease, List<RevCommit> commits, RevCommit veryFirstCommit) {
        for(RevCommit commit : commits) {
            if(veryFirstCommit == null) {
                veryFirstCommit = commit;
            }

            processCommitChanges(releaseIndex, currentRelease, commit);
        }
    }

    private void processCommitChanges(int releaseIndex, Release currentRelease, RevCommit commit) {
        List<String> modifiedFiles = gitHubInfoRetrieve.getDifference(commit, false);
        List<String> addedFiles = gitHubInfoRetrieve.getDifference(commit, true);
        Date commitDate = Date.from(commit.getCommitterIdent().getWhenAsInstant());
        String authorName = commit.getAuthorIdent().getName();

        updateChangesForRelease(releaseIndex, currentRelease, modifiedFiles, addedFiles, commitDate);
        updateNAuth(modifiedFiles, currentRelease, authorName);
    }

    private void updateChangesForRelease(int releaseIndex, Release currentRelease,
                                         List<String> modifiedFiles, List<String> addedFiles, Date commitDate) {
        if(!modifiedFiles.isEmpty()) {
            updateNr(modifiedFiles, currentRelease);
            Release previousRelease = (releaseIndex > 0) ? releaseList.get(releaseIndex - 1) : currentRelease;
            calculateDateOfCreation(currentRelease, previousRelease, commitDate, addedFiles);
        }
    }

    private void calculateAge(List<Release> releaseList){
        int len = releaseList.size();
        for(int i = 0; i < len; i++){
            Release currRelease = releaseList.get(i);
            List<ClassFile> allReleaseFiles = currRelease.getReleaseAllClass();
            if(i == 0){
                for(ClassFile file: allReleaseFiles){
                    int age = (int) ((currRelease.getDate().getTime() - file.getCreationDate().getTime()) / 86400000);
                    file.setAge(age);
                }
                continue;
            }
            Release precRelease = releaseList.get(i-1);
            for(ClassFile file:allReleaseFiles){
                ClassFile preFile;
                try{
                    preFile = precRelease.getClassFileByKey(ClassFile.getKey(file));
                    int age = (int) ((file.getCreationDate().getTime() - preFile.getCreationDate().getTime()) /86400000);
                    age = age + preFile.getAge();
                    // Ensure age is never less than the previous age
                    if (age < preFile.getAge()) {
                        age = preFile.getAge();
                    }
                    file.setAge(age);
                }
                catch(Exception e){
                    int age = (int) ((currRelease.getDate().getTime() - file.getCreationDate().getTime()) / 86400000);
                    file.setAge(age);
                }
            }
        }
    }


    private void creationDateSetter(List<ClassFile> classFiles,RevCommit firstCommit){
        for (ClassFile file : classFiles) {
            if (file.getCreationDate() == null) {
                file.setCreationDate(Date.from(firstCommit.getCommitterIdent().getWhenAsInstant()));
            }
        }
    }

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


    private void parserFiles(List<String> addedFiles, Release precRelease, Release currentRelease, Date commitDate) {
        addedFiles.forEach(file ->
                processFile(file, precRelease, currentRelease, commitDate));
    }

    private void processFile(String file, Release precRelease, Release currentRelease, Date commitDate) {
        List<ClassFile> precFiles = precRelease.getClassFileByPath(file);
        List<ClassFile> currFiles = currentRelease.getClassFileByPath(file);

        if (precFiles.isEmpty()) {
            updateNewClassFiles(currFiles, commitDate);
        } else {
            updateExistingClassFiles(currFiles, commitDate);
        }
    }

    private void updateNewClassFiles(List<ClassFile> currFiles, Date commitDate) {
        currFiles.stream()
                .filter(Objects::nonNull)
                .forEach(currFile -> updateFileCreationDate(currFile, commitDate));
    }

    private void updateExistingClassFiles(List<ClassFile> currFiles, Date commitDate) {
        currFiles.stream()
                .filter(Objects::nonNull)
                .forEach(currFile -> currFile.setCreationDate(commitDate));
    }

    private void updateFileCreationDate(ClassFile currFile, Date commitDate) {
        if (currFile.getCreationDate() == null || commitDate.before(currFile.getCreationDate())) {
            currFile.setCreationDate(commitDate);
        }
    }

    // Possibile correzione nel filterCommitsByRelease
    Map<RevCommit,Release> filterCommitsByRelease(Release targetRelease) {
        Map<RevCommit,Release> commitReleaseMap = new HashMap<>();

        // Mantieni una lista ordinata di release fino a quella target
        List<Release> relevantReleases = releaseList.stream()
                .filter(r -> r.getId() < targetRelease.getId())
                .sorted(Comparator.comparing(Release::getDate))
                .toList();

        // For each release, associate the commits to the correct release
        for (Release currentRelease : relevantReleases) {
            for (RevCommit commit : currentRelease.getAllReleaseCommits()) {
                // Find the appropriate release based on the commit date
                Release appropriateRelease = relevantReleases.stream()
                        .filter(r -> r.getDate().toInstant().isAfter(commit.getCommitterIdent().getWhenAsInstant()))
                        .findFirst()
                        .orElse(targetRelease);

                commitReleaseMap.put(commit, appropriateRelease);
            }
        }
        // Usa una Map per contare le occorrenze uniche
        Map<Release, Integer> releaseCount = new HashMap<>();

// Conta le occorrenze di ogni release
        for (Release release : commitReleaseMap.values()) {
            releaseCount.merge(release, 1, Integer::sum);
        }

        LOGGER.debug("after filtering commit for the release {} ", targetRelease.getName());

// Stampa i conteggi una sola volta per release
        releaseCount.forEach((release, count) ->
                LOGGER.debug("found commits {} for the release {} ", count, release.getName())
        );


        return commitReleaseMap;
    }
    private Map<String, MethodInstance> calculateCKMetrics(RevCommit commit, Path sourcePath, Release release) {
        Map<String,MethodInstance> methodInstanceResults = new HashMap<>();
        List<MethodInstance> methodsChanged = fillMethodsBuggy(commit);
        CK ck = new CK();
        // POX implementation by removing the methods in CKRESULT SO THAT
        // THE PROCESS IS ONLY FOR THE CHANGED METHOD
        ck.calculate(sourcePath, classResult -> processClassResult(
                classResult, release, sourcePath, methodsChanged, methodInstanceResults));
        return methodInstanceResults;
    }
    private Map<String, MethodInstance> processRefactoredMethods(){
        Map<String,MethodInstance> methodInstanceResults = new HashMap<>();
        if(project.isRefactoredMethodsFilled()){
            return project.getFilledRefactoredMethods();
        }
        List<MethodInstance> methodsChanged=project.getInitializedRefactoredMethods();
        Path sourcePath=project.getRefactoredSourcePath();
        Release release=project.getRefactoredRelease(releaseList);
        CK ck = new CK();
        ck.calculate(sourcePath, classResult -> processClassResult(
                classResult, release, sourcePath, methodsChanged, methodInstanceResults));
        project.setMethods(methodInstanceResults);
        return methodInstanceResults;

    }

    private void processClassResult(CKClassResult classResult, Release release, Path sourcePath,
                                    List<MethodInstance> changedMethod, Map<String,MethodInstance> methodInstanceResults) {

        if (classResult.getMethods() == null || classResult.getMethods().isEmpty()) {
            return;
        }
        ClassFile filledClass = release.findClassFileByApproxName(classResult.getClassName());
        if (filledClass == null) {
            return;
        }

        // Filter methods before processing to improve performance
        classResult.getMethods().forEach(method -> {
            // Check if this method is in the list of changed methods before collecting metrics
            MethodInstance methodInstance = isMethodChanged(method, filledClass, changedMethod);
            if (methodInstance!=null) {
                // Only collect metrics for methods that have changed
                int nSmell = PmdRunner.collectCodeSmellMetricsClass(classResult.getClassName(), sourcePath.toString(),
                        method.getStartLine(), method.getStartLine() + method.getLoc());
                processMethod(method, filledClass, methodInstance, release, methodInstanceResults, nSmell);
            }
        });
    }

    /**
     * Checks if a method is in the list of changed methods
     * @param method The method to check
     * @param filledClass The class containing the method
     * @param methodChanged The list of changed methods
     * @return true if the method is in the list of changed methods, false otherwise
     */
    private MethodInstance isMethodChanged(CKMethodResult method, ClassFile filledClass, List<MethodInstance> methodChanged) {
        String methodName = MethodInstance.cleanMethodName(method.getMethodName());
        for(MethodInstance methodInstance: methodChanged){
            if(methodName.equals(methodInstance.getMethodName())
                    && filledClass.getPath().contains(methodInstance.getClassPath())
            ){
                return methodInstance;
            }
        }
        return null;
    }

    private void processMethod(CKMethodResult method, ClassFile filledClass, MethodInstance filledMethod,
                               Release release, Map<String,MethodInstance> methodInstanceResults, int nSmell) {
        // We already checked if the method is in the list of changed methods in processClassResult
        // This is just to get the method name
        try {
            MethodInstance methodInstance = createMethodInstance(method, filledClass, filledMethod, release, nSmell);

            methodInstanceResults.put(MethodInstance.createMethodKey(methodInstance), methodInstance);
        } catch (Exception e) {
            LOGGER.error("Errore durante l'analisi del metodo: {} - {}",
                    method.getQualifiedMethodName(), e.getMessage(), e);
        }
    }

    private MethodInstance createMethodInstance(CKMethodResult method, ClassFile filledClass ,
                                                MethodInstance filledMethod, Release release, int nSmell) {

        MethodInstance methodInstance = new MethodInstance();
        methodInstance.setClassPath(filledClass.getPath());
        methodInstance.setClassName(filledClass.getClassName());
        methodInstance.setMethodName(filledMethod.getMethodName());
        methodInstance.setReleaseName(release.getName());

        // Imposta le metriche
        setMethodMetrics(methodInstance, method, filledClass,nSmell);

        filledClass.addMethod(methodInstance);
        return methodInstance;
    }
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
        // Metriche della classe
        methodInstance.setAge(filledClass.getAge());
        methodInstance.setnAuth(filledClass.getnAuth());
        methodInstance.setNr(filledClass.getNR());

        methodInstance.setBuggy(false);
    }


    Map<RevCommit, List<MethodInstance>> changedMethods=new ConcurrentHashMap<>();
    List<MethodInstance> fillMethodsBuggy(RevCommit commit) {
        return changedMethods.computeIfAbsent(commit, gitHubInfoRetrieve::getChangedMethodInstances);
    }
    private void reorderReleaseResults(ReleaseData releaseData){
        String methodKey;
        for(String  commitHash: releaseData.commitsByHash.keySet()){ // iterate on only the commit for the actual release
            Map<String,MethodInstance> methods=resultCommitsMethods.get(commitHash);
            if(methods==null){
                continue;
            }
            for(MethodInstance method: methods.values()){
                methodKey=MethodInstance.createMethodKey(method);
                if(releaseData.releaseResults.containsKey(methodKey) && releaseData.releaseResults.get(methodKey).getAge()<method.getAge()){
                    releaseData.releaseResults.put(methodKey,method);
                }
            }
        }
    }

    private void assignBuggyness(ReleaseData data) {
        reorderReleaseResults(data);
        List<Ticket> refactoredTickets=new ArrayList<>();
        if(project.afterRefactoredRelease(data.release,releaseList)){
            Map<String,MethodInstance> refactoredMethods=processRefactoredMethods();
            data.releaseResults.putAll(refactoredMethods);
        }
        if (!resultsChanged) return;
        resultsChanged = false;
        LOGGER.info("Initializing buggyness assignment");
        // Reset buggyness for all methods
        data.releaseResults.values().forEach(method -> method.setBuggy(false));
        // If there are no tickets, terminate
        if (data.releaseTickets == null || data.releaseTickets.isEmpty()) {
            LOGGER.info("No tickets found for this release");
            return;
        }



        // Create index for methods by release
        Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease = new HashMap<>();
        data.releaseResults.values().forEach(method -> {
            if (method.getReleaseName() != null) {
                int releaseId = Release.getId(method.getReleaseName(), releaseList);
                String methodKey = method.getClassPath() + "#" +method.getClassName()+ "#" + method.getMethodName();
                methodsByRelease
                        .computeIfAbsent(releaseId, k -> new HashMap<>())
                        .computeIfAbsent(methodKey, k -> new ArrayList<>())
                        .add(method);
            }
        });

        // Process the tickets
        for (Ticket ticket : data.releaseTickets) {
            Release checkInj = ticket.getIv() != null ? ticket.getIv() : ticket.getCalculatedIv();
            if (checkInj == null ) {
                continue;
            }
            processTicketChanges(ticket, methodsByRelease ,refactoredTickets,data.commitsByHash );
        }
        project.setTickets(data.release,refactoredTickets );
        LOGGER.info("Buggyness assignment completed");
    }


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

    private boolean isValidCommit(Map<String, MethodInstance> commitMethods,
                                  List<MethodInstance> methodsChanged,
                                  RevCommit commit) {
        return commitMethods != null && !methodsChanged.isEmpty() && commit != null;
    }

    private Set<String> extractModifiedSignatures(List<MethodInstance> methodsChanged) {
        Set<String> signatures = new HashSet<>();
        for (MethodInstance changedMethod : methodsChanged) {
            signatures.add(changedMethod.getClassPath() + "#" +changedMethod.getClassName()+ "#" + changedMethod.getMethodName());
        }
        return signatures;
    }


    private void updateRefactoredTickets(Ticket ticket,
                                         List<MethodInstance> changedMethods,
                                         List<Ticket> refactoredTickets) {
        boolean shouldAdd = changedMethods.stream()
                .anyMatch(this::isMethodToRefactor);

        if (shouldAdd && !refactoredTickets.contains(ticket)) {
            refactoredTickets.add(ticket);
        }
    }

    private boolean isMethodToRefactor(MethodInstance method) {
        return project.getMethodToRefactor().getMethodName().contains(method.getMethodName()) &&
                method.getClassPath().contains(project.getMethodToRefactor().getClassPath());
    }

    private static class MethodData {
        final List<MethodInstance> changedMethods;
        final Set<String> modifiedSignatures;

        MethodData(List<MethodInstance> changedMethods, Set<String> modifiedSignatures) {
            this.changedMethods = changedMethods;
            this.modifiedSignatures = modifiedSignatures;
        }
    }

    private List<RevCommit> getSortedCommit(List<RevCommit> associatedCommits) {
        sortCommits(associatedCommits);
        return associatedCommits;
    }

    private void updateBuggyness(Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease,
                                 Set<String> modifiedMethodSignatures,
                                 int injectedId,
                                 int fixedId) {
        // Verifica parametri di input
        if (injectedId >= fixedId) {
            LOGGER.warn("Invalid release range: injectedId {} >= fixedId {}", injectedId, fixedId);
            return;
        }

        for (int releaseId = injectedId; releaseId < fixedId; releaseId++) {
            Map<String, List<MethodInstance>> releaseMethods = methodsByRelease.get(releaseId);
            if (releaseMethods == null) {
                continue;
            }

            for (String methodSignature : modifiedMethodSignatures) {
                List<MethodInstance> methods = releaseMethods.get(methodSignature);
                if (methods != null && !methods.isEmpty()) {
                    for (MethodInstance method : methods) {
                        // Verifica se il metodo non è già stato marcato come buggy
                        if (!method.isBuggy()) {
                            method.setBuggy(true);
                            // Log per tracciare l'assegnazione della buggyness
                            LOGGER.debug("Marked method as buggy: {} in release {}",
                                    methodSignature, releaseId);
                        }
                    }
                }
            }
        }
    }


    //un metodo utile per ordinare i commit in ordine temporale
    private void sortCommits(List<RevCommit> commits){
        Collections.sort(commits,new RevCommitComparator());
    }

    //il comparator utile a sortCommits
    private class RevCommitComparator implements Comparator<RevCommit> {
        @Override
        public int compare(RevCommit a, RevCommit b) {
            return a.getCommitterIdent().getWhenAsInstant().compareTo(b.getCommitterIdent().getWhenAsInstant());
        }
    }
}
