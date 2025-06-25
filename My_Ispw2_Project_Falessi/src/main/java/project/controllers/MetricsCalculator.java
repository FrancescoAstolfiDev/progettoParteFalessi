
package project.controllers;

import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKClassResult;
import com.github.mauricioaniche.ck.CKMethodResult;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.LoggerFactory;
import project.models.*;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import project.utils.ConstantSize;
import project.utils.ConstantsWindowsFormat;

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


    /**
     * Constructor that takes a GitHubInfoRetrieve object and a project name
     */
    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve, String projectName) throws IOException {
        this.gitHubInfoRetrieve = gitHubInfoRetrieve;
        this.projectName = projectName;
        Files.createDirectories(Paths.get(String.valueOf(tempDirPath)));
        Path cacheDirPath = ConstantsWindowsFormat.CACHE_PATH;
        Files.createDirectories(cacheDirPath);

        // Initialize the repository manager
        this.repositoryManager = new RepositoryManager(gitHubInfoRetrieve);
        this.resultsChanged=false;
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
                if(method.getRelease().equals(release)){
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
                releaseData.commitsByHash.put(commitHash, commitCheck.commit);
                if(commitCheck.methods==null){                  // No commit from cache i have to process it
                    releaseData.commitHashesToProcess.add(commitHash);
                    continue;
                }
                Map<String,MethodInstance> commitMetrics=new HashMap<>();
                for( MethodInstance method: commitCheck.methods){
                    method.setRelease(release);
                    ClassFile classFile=release.getClassFileByPath(method.getFilePath());
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
            LOGGER.info("\n\n  Thread {} in progress... commits analyzed {}  commits to process {}) \n\n",
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
        int cachedCommitsSize = data.releaseCommits.size() - data.commitHashesToProcess.size();

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



    public  void calculateAll(List<Release> releaseList) {
        LOGGER.info("processing class metrics");
        RevCommit veryFirstCommit = null;
        this.releaseList = releaseList;
        int len = releaseList.size();
        for(int i = 0; i < len; i++){
            Release currRelease = releaseList.get(i);

            List<ClassFile> classFiles = currRelease.getReleaseAllClass();

            List<RevCommit> revCommitList = currRelease.getAllReleaseCommits();
            RevCommit firstCommit = revCommitList.get(0);

            for(RevCommit commit:revCommitList){
                if(veryFirstCommit == null){
                    veryFirstCommit = commit;
                }

                List<String> modifiedFiles = gitHubInfoRetrieve.getDifference(commit,false);
                List<String> addedFiles = gitHubInfoRetrieve.getDifference(commit,true);
                String authorName = commit.getAuthorIdent().getName();
                if(!modifiedFiles.isEmpty() && i == 0) {
                    updateNr(modifiedFiles, currRelease);
                    calculateDateOfCreation(currRelease,currRelease, Date.from(commit.getCommitterIdent().getWhenAsInstant()),addedFiles);
                }
                else if(!modifiedFiles.isEmpty()){
                    updateNr(modifiedFiles, currRelease);
                    calculateDateOfCreation(currRelease,releaseList.get(i-1),Date.from(commit.getCommitterIdent().getWhenAsInstant()),addedFiles);
                }
                updateNAuth(modifiedFiles,currRelease,authorName);
            }

            creationDateSetter(classFiles,firstCommit);



        }
        calculateAge(releaseList);
        LOGGER.info("end of processing class metrics");
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
                    preFile = precRelease.getClassFileByPath(file.getPath());
                    int age = (int) ((file.getCreationDate().getTime() - preFile.getCreationDate().getTime()) /86400000);
                    age = age + preFile.getAge();
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

    private void updateNAuth(List<String> modifiedFiles,Release release,String authName){
        for(String path:modifiedFiles){
            ClassFile file = release.getClassFileByPath(path);
            if (file != null){
                file.addAuthor(authName);
            }
        }
    }
    private void updateNr(List<String> modifiedFiles,Release release){
        for(String path:modifiedFiles){
            ClassFile file = release.getClassFileByPath(path);
            if(file != null){
                file.incrementNR();
            }
        }
    }

    private void calculateDateOfCreation(Release currentRelease, Release precRelease, Date commitDate, List<String> addedFiles){
        if(currentRelease.getId() == precRelease.getId()){
            for(String file:addedFiles){
                ClassFile currFile = currentRelease.getClassFileByPath(file);
                if(currFile != null && (currFile.getCreationDate() == null || currFile.getCreationDate().after(commitDate))){
                    currFile.setCreationDate(commitDate);
                }
            }
            return;
        }
        parserFiles(addedFiles,precRelease,currentRelease,commitDate);
    }

    private void parserFiles(List<String> addedFiles,Release precRelease,Release currentRelease,Date commitDate){
        for(String file:addedFiles){
            ClassFile precFile = precRelease.getClassFileByPath(file);
            //precFile == null se nella release precedente era presente la classe java in questione
            if(precFile == null){
                ClassFile currFile = currentRelease.getClassFileByPath(file);
                if(currFile != null && currFile.getCreationDate() != null){
                    if(commitDate.before(currFile.getCreationDate())){
                        currFile.setCreationDate(commitDate);
                    }
                }
                else if(currFile != null){
                    currFile.setCreationDate(commitDate);
                }
            }
            //qui la classe java è stata introdotta nella più recente release
            else if(currentRelease.getClassFileByPath(file) != null){
                currentRelease.getClassFileByPath(file).setCreationDate(commitDate);
            }
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
        ck.calculate(sourcePath, classResult -> processClassResult(
                classResult, release, sourcePath, methodsChanged, methodInstanceResults));
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


        classResult.getMethods().forEach(method ->{
                    int nSmell=PmdRunner.collectCodeSmellMetricsClass(classResult.getClassName(),sourcePath.toString(),method.getStartLine(),method.getStartLine()+method.getLoc());
                    processMethod(method, filledClass, changedMethod, release, methodInstanceResults, nSmell);
                }
        );
    }

    private void processMethod(CKMethodResult method, ClassFile filledClass, List<MethodInstance> methodChanged,
                               Release release, Map<String,MethodInstance> methodInstanceResults, int nSmell) {

        boolean check=false;
        String methodName="anonymous";
        for(MethodInstance methodInstance: methodChanged){
            if(method.getMethodName().contains(methodInstance.getMethodName())
                    && filledClass.getPath().equals(methodInstance.getFilePath())
            ){
                methodName=methodInstance.getMethodName();
                check=true;
                break;
            }
        }
        if (!check) return;

        try {
            MethodInstance methodInstance = createMethodInstance(method, filledClass, methodName, release, nSmell);

            methodInstanceResults.put(MethodInstance.createMethodKey(methodInstance), methodInstance);
        } catch (Exception e) {
            LOGGER.error("Errore durante l'analisi del metodo: {} - {}",
                    method.getQualifiedMethodName(), e.getMessage(), e);
        }
    }

    private MethodInstance createMethodInstance(CKMethodResult method, ClassFile filledClass ,
                                                String methodName, Release release, int nSmell) {

        MethodInstance methodInstance = new MethodInstance();
        methodInstance.setFilePath(filledClass.getPath());
        methodInstance.setMethodName(methodName);
        methodInstance.setRelease(release);

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


    private void assignBuggyness(ReleaseData data) {
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
            if (method.getRelease() != null) {
                int releaseId = Release.getId(method.getRelease(), releaseList);
                String methodKey = method.getFilePath() + "#" + method.getMethodName();
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
            processTicketChanges(ticket, methodsByRelease);
        }

        LOGGER.info("Buggyness assignment completed");
    }

    private void processTicketChanges(Ticket ticket,
                                      Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease) {
        Release injected = ticket.getIv() != null ? ticket.getIv() : ticket.getCalculatedIv();
        Release fixed = ticket.getFv();

        for (RevCommit commit : getSortedCommit(ticket.getAssociatedCommits())) {
            String commitHash = commit.getId().getName();

            // Get the methods modified by the commit
            List<MethodInstance> methodsChanged = fillMethodsBuggy(commit);
            Map<String, MethodInstance> commitMethods = resultCommitsMethods.get(commitHash);

            if (commitMethods == null || methodsChanged.isEmpty()) {
                continue;
            }

            // Create set of actually modified methods
            Set<String> modifiedMethodSignatures = new HashSet<>();
            for (MethodInstance changedMethod : methodsChanged) {
                for (MethodInstance commitMethod : commitMethods.values()) {
                    if (commitMethod.getMethodName().equals(changedMethod.getMethodName()) && commitMethod.getFilePath().equals(changedMethod.getFilePath())) {
                        modifiedMethodSignatures.add(commitMethod.getFilePath() + "#" + commitMethod.getMethodName());
                    }
                }
            }

            // Update buggyness for the affected releases
            updateBuggyness(methodsByRelease, modifiedMethodSignatures, injected.getId(), fixed.getId());
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
        // For each release in the range
        for (int releaseId = injectedId; releaseId < fixedId; releaseId++) {
            Map<String, List<MethodInstance>> releaseMethods = methodsByRelease.get(releaseId);
            if (releaseMethods != null) {
                // Update only the modified methods
                for (String methodSignature : modifiedMethodSignatures) {
                    List<MethodInstance> methods = releaseMethods.get(methodSignature);
                    if (methods != null) {
                        methods.forEach(method -> method.setBuggy(true));
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