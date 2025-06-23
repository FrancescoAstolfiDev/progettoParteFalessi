
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
import java.util.stream.Collectors;

import project.utils.ConstantSize;
import project.utils.ConstantsWindowsFormat;

import org.slf4j.Logger;

public class MetricsCalculator {

    private final Logger LOGGER = LoggerFactory.getLogger(MetricsCalculator.class);
    private final Path tempDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "ck_analysis");
    private List<Release> releaseList;
    private GitHubInfoRetrieve gitHubInfoRetrieve;
    private String projectName;
    private RepositoryManager repositoryManager;
    private boolean resultsChanged;
    private Map <String,Map<String,MethodInstance>> resultCommitsMethods=new HashMap<>();


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
        public Set<MethodInstance> getMethods(){
            return methods;
        }

    }



    void getCommitsInCache(ReleaseData releaseData ) {
        List<Release> relevantReleases = releaseList.stream()
                .filter(r -> r.getId() < releaseData.release.getId())
                .toList();

        // Popola la reference map per le release che non sono ancora state processate
        int commitCached=0;
        int commitToProcess=0;
        for (Release release : relevantReleases) {
            if (!referenceMap.containsKey(release)) {
                Set<CommitCheck> releaseCommits = new HashSet<>();
                for (RevCommit commit : release.getAllReleaseCommits()) {
                    String commitHash = commit.getId().getName();
                    if (resultCommitsMethods.containsKey(commitHash)) {
                        CommitCheck commitCheck = new CommitCheck(commit);
                        commitCheck.addMethods(resultCommitsMethods.get(commitHash).values());
                        releaseCommits.add(commitCheck);
                        commitCached++;
                    }else{
                        CommitCheck commitCheck = new CommitCheck(commit);
                        releaseCommits.add(commitCheck);
                        commitToProcess++;
                    }
                }
                referenceMap.put(release, releaseCommits);
            }
            LOGGER.info("release {} commit cached {} commit to process {}", release.getName(), commitCached, commitToProcess);
        }

        // Log dei risultati
        LOGGER.info(" after the association commit in cache ");
        int countMethods=0;
        int countCommits=0;
        for(Release release :relevantReleases){
            for(CommitCheck commitCheck: referenceMap.get(release)){
                for(MethodInstance method: commitCheck.methods){
                    countMethods++;
                }
                countCommits++;
            }
            LOGGER.debug("found methods {}  in {} commits for the release {} ", countMethods,countCommits, release.getName());
        }

        for(Release release: relevantReleases){
            for( CommitCheck commitCheck: referenceMap.get(release)){
                String commitHash = commitCheck.commit.getId().getName();
                if(commitCheck.methods==null){
                    releaseData.commitHashesToProcess.add(commitHash);
                }else{
                    Map<String,MethodInstance> commitMetrics=new HashMap<>();
                    for( MethodInstance method: commitCheck.methods){
                        method.setRelease(release);
                        ClassFile classFile=release.getClassFileByPath(method.getFilePath());
                        if(classFile!=null)classFile.addMethod(method);
                        commitMetrics.put(MethodInstance.createMethodKey(method),method);
                    }
                    releaseData.releaseResults.putAll(commitMetrics);
                    releaseData.commitsAnalyzed.put(commitHash, commitCheck.commit);
                    resultsChanged=true;
                }
            }
        }

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

    void processCommits(ReleaseData releaseData) throws IOException, ExecutionException, InterruptedException {
        System.out.println("processing the other commits ");
        // Crea un lock per sincronizzare l'accesso al repository
        Object threadLock = new Object();

        // Separa l'ultimo commit per elaborazione sequenziale
        AtomicInteger countThread = new AtomicInteger();
        // Limita il numero di thread in base alla memoria disponibile e ai core
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // Usa meno thread se la memoria è limitata (< 2GB)
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int memoryBasedThreads = (int) Math.max(1, Math.min(maxMemory / 512, ConstantSize.NUM_THREADS));
        int numThreads = Math.min(availableProcessors, memoryBasedThreads);

        LOGGER.info("Memoria massima disponibile: {} MB, Numero di thread: {}", maxMemory, numThreads);

        // Dividi i commit in batch più piccoli per ridurre il consumo di memoria
        List<String> commitHashList = new ArrayList<>(releaseData.commitHashesToProcess);
        int batchSize = Math.min(100, commitHashList.size()); // Processa al massimo 100 commit alla volta

        // Crea il backup iniziale
        repositoryManager.backupRepository();

        // Indice del batch corrente
        int currentBatchIndex = 0;

        // Flag per indicare se è necessario riavviare l'elaborazione dopo un reset
        boolean restartProcessing=false;

        // Thread pool finale che verrà utilizzato anche per processare le classi rimanenti in cache
        ForkJoinPool finalThreadPool = null;

        String finalCommitHash = commitHashList.get(commitHashList.size() - 1);

        // Processa i commit in batch con possibilità di riavvio
        while (currentBatchIndex < commitHashList.size()) {
            // Reset del flag di riavvio
            restartProcessing = false;

            // Crea un nuovo thread pool per ogni ciclo di elaborazione
            // Questo permette di riavviare completamente l'elaborazione dopo un reset
            ForkJoinPool customThreadPool = new ForkJoinPool(numThreads);

            // Aggiorna il thread pool finale
            finalThreadPool = customThreadPool;

            try {
                // Calcola l'indice di fine per il batch corrente
                int endIndex = Math.min(currentBatchIndex + batchSize, commitHashList.size());
                List<String> batchCommits = commitHashList.subList(currentBatchIndex, endIndex);

                LOGGER.info("Elaborazione batch di commit {}/{} (dimensione: {})",
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

                                    // Suggerisci al GC di liberare memoria non utilizzata
                                    if (countThread.get() % 10 == 0) {
                                        System.gc();
                                    }
                                } catch (OutOfMemoryError e) {
                                    LOGGER.error("Memoria insufficiente durante l'elaborazione del commit: {}", commitHash, e);
                                    System.gc();
                                } catch (Exception e) {
                                    LOGGER.error("Errore durante l'elaborazione del commit: {}", commitHash, e);
                                }
                            })
                        ).get(); // Attendi il completamento
                } catch (Exception e) {
                    LOGGER.error("Errore durante l'elaborazione del batch: {}", e.getMessage(), e);

                    customThreadPool.shutdown();

                    // Esegui il reset completo
                    restartProcessing = handleProcessingError(releaseData, true);
                }
                // Salva lo stato intermedio dopo ogni batch
                Caching.saveCommitCache(resultCommitsMethods, projectName);
                // Suggerisci al GC di liberare memoria dopo ogni batch
                System.gc();

                // Breve pausa per permettere al sistema di stabilizzarsi
                Thread.sleep(1000);

                // Passa al batch successivo
                currentBatchIndex += batchSize;
            } finally {
                // Assicurati che il thread pool venga chiuso
                customThreadPool.shutdown();
            }
        }

        // Se è stato richiesto un riavvio, richiama ricorsivamente questo metodo
        if (restartProcessing) {
            LOGGER.info("Riavvio dell'elaborazione dopo il reset completo...");
            // Rimuovi i commit già elaborati dalla lista da processare
            releaseData.commitHashesToProcess.removeAll(releaseData.commitsAnalyzed.keySet());
            // Richiama ricorsivamente il metodo per elaborare i commit rimanenti
            processCommits(releaseData);
            return;
        }

        // Completamento normale dell'elaborazione
        // Usa lo stesso thread pool per processare le classi rimanenti in cache
        if (finalThreadPool ==null) {
            // Fallback nel caso in cui il thread pool non sia stato creato
            LOGGER.warn("Thread pool non disponibile, elaborazione sequenziale");
            Caching.saveCommitCache(resultCommitsMethods, projectName);
            repositoryManager.restoreFromBackup();

        }else {
            Caching.saveCommitCache(resultCommitsMethods, projectName);
            assignBuggyness(releaseData);
            ClassWriter.writeResultsToFile(releaseData.release, projectName, releaseData.releaseResults, releaseData.dataSetType);
        }
    }

    // Flag per indicare se è in corso un reset completo
    private volatile boolean resetInProgress = false;

    /**
     * Gestisce un errore durante l'elaborazione, eseguendo sempre un reset completo.
     * 
     * @param releaseData I dati della release in elaborazione
     * @param forceReset Parametro mantenuto per compatibilità, non utilizzato
     * @return true se è stato eseguito un reset completo, false altrimenti
     */
    private boolean handleProcessingError(ReleaseData releaseData, boolean forceReset) {
        // Prima di tentare il ripristino, suggerisci al GC di liberare memoria
        System.gc();

        boolean resetPerformed = false;

        try {
            // Log delle informazioni di memoria per il debug
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory() / (1024 * 1024);
            long freeMemory = runtime.freeMemory() / (1024 * 1024);
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory() / (1024 * 1024);

            LOGGER.info("Stato memoria durante l'errore: Usata {}MB, Libera {}MB, Totale {}MB, Max {}MB",
                    usedMemory, freeMemory, totalMemory, maxMemory);

            if (!resetInProgress) {
                // Imposta il flag per evitare reset concorrenti
                resetInProgress = true;

                LOGGER.warn("Errore rilevato. Esecuzione reset completo...");

                // Attendi un momento per permettere al sistema di stabilizzarsi
                Thread.sleep(10000);

                // Tenta di ripristinare il backup
                gitHubInfoRetrieve.initializingRepo();
                // Crea un nuovo backup per ripartire da uno stato pulito
                LOGGER.info("Creazione di un nuovo backup dopo il reset...");
                repositoryManager.backupRepository();

                // Salva lo stato corrente per non perdere il lavoro fatto finora
                if (!resultCommitsMethods.isEmpty()) {
                    LOGGER.info("Salvataggio dello stato corrente dopo il reset...");
                    Caching.saveCommitCache(resultCommitsMethods, projectName);
                }

                resetPerformed = true;

                // Reset completato
                resetInProgress = false;

                LOGGER.info("Reset completo eseguito con successo. Riavvio dell'elaborazione...");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Operazione interrotta durante la gestione dell'errore", e);
        } catch (Exception restoreError) {
            LOGGER.error("Errore durante il ripristino del backup: {}",
                    restoreError.getMessage(), restoreError);
        }

        return resetPerformed;
    }

    /**
     * Versione semplificata che non forza il reset
     */
    private boolean handleProcessingError(ReleaseData releaseData) {
        return handleProcessingError(releaseData, false);
    }


    private void outData(int log, ReleaseData releaseData) {
        if ((log % ConstantSize.FREQUENCY_LOG) == 0) {
            int totalCommits = releaseData.releaseCommits.size() - releaseData.commitsAnalyzed.size();
            int processedCommits = releaseData.commitsAnalyzed.size();

            LOGGER.info("\n\n  Thread {} in corso... commits analyzed {}  commits to process {}) \n\n",
                    log,
                    processedCommits,
                    totalCommits);
        }
        if ((log % ConstantSize.FREQUENCY_WRITE_CACHE) == 0) {
           Caching.saveCommitCache(resultCommitsMethods, projectName);
        }
        if ((log % ConstantSize.FREQUENCY_WRITE_CSV) == 0  && releaseData.dataSetType.equals(DataSetType.TRAINING)) {
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
        LOGGER.info((" \n\n inizio calcolo metriche per la release " + release.getName()));

        // Numero ottimale di thread basato sui core disponibili
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
            System.out.println("No commits to process for release ");
            assignBuggyness(data);
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, dataSetType);
            return;
        }
        if (!data.releaseResults.isEmpty() && data.commitHashesToProcess.size()>ConstantSize.FREQUENCY_WRITE_CSV && dataSetType.equals(DataSetType.TRAINING)) {
            // condition !data.releaseResults.isEmpty() && data.commitHashesToProcess.size()>ConstantSize.FREQUENCY_WRITE_CSV && dataSetType.equals(DataSetType.TRAINING)
            System.out.println("writing before the elaboration");
            assignBuggyness(data);
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, DataSetType.PARTIAL);
        }

        // Process only the commits that aren't in the cache
        int maxRetries = 3; // Numero massimo di tentativi di elaborazione completa
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // Se non è il primo tentativo, log informativo
                if (attempt > 0) {
                    LOGGER.info("Tentativo {} di {} per l'elaborazione della release {}", 
                            attempt + 1, maxRetries, release.getName());
                }

                processCommits(data);
               assignBuggyness(data);

                // Se arriviamo qui, l'elaborazione è stata completata con successo
                LOGGER.info("Elaborazione della release {} completata con successo", release.getName());
                break;

            } catch (IOException | ExecutionException e) {
                LOGGER.error("Errore durante l'elaborazione della release {}: {}", 
                        release.getName(), e.getMessage(), e);

                // Gestisci l'errore e determina se è necessario un nuovo tentativo
                boolean resetPerformed = handleProcessingError(data);

                if (!resetPerformed && attempt == maxRetries - 1  && dataSetType.equals(DataSetType.TRAINING)) {
                    // Ultimo tentativo fallito senza reset, log di errore finale
                    LOGGER.error("Impossibile completare l'elaborazione della release {} dopo {} tentativi", 
                            release.getName(), maxRetries);
                    Caching.saveCommitCache(resultCommitsMethods,projectName);
                    assignBuggyness(data);
                    ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, dataSetType);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                LOGGER.error("Elaborazione interrotta per la release {}", release.getName(), e);

                // Gestisci l'errore di interruzione
                handleProcessingError(data);

                // Non ritentiamo in caso di interruzione esplicita
                LOGGER.warn("Elaborazione della release {} interrotta dall'utente", release.getName());
                break;
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
                    calculateDateOfCreation(currRelease,currRelease,commit.getCommitterIdent().getWhen(),addedFiles);
                }
                else if(!modifiedFiles.isEmpty()){
                    updateNr(modifiedFiles, currRelease);
                    calculateDateOfCreation(currRelease,releaseList.get(i-1),commit.getCommitterIdent().getWhen(),addedFiles);
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
                file.setCreationDate(firstCommit.getCommitterIdent().getWhen());
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
                .collect(Collectors.toList());

        // Per ogni release, associa i commit alla release corretta
        for (Release currentRelease : relevantReleases) {
            for (RevCommit commit : currentRelease.getAllReleaseCommits()) {
                // Trova la release appropriata basandosi sulla data del commit
                Release appropriateRelease = relevantReleases.stream()
                        .filter(r -> r.getDate().after(commit.getCommitterIdent().getWhen()))
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
                classResult, release, sourcePath, methodsChanged, methodInstanceResults,commit));
        return methodInstanceResults;
    }

    private void processClassResult(CKClassResult classResult, Release release, Path sourcePath,
                                    List<MethodInstance> changedMethod, Map<String,MethodInstance> methodInstanceResults, RevCommit commit) {

        if (classResult.getMethods() == null || classResult.getMethods().isEmpty()) {
            return;
        }

        ClassFile filled_class = release.findClassFileByApproxName(classResult.getClassName());
        if (filled_class == null) {
            return;
        }


        classResult.getMethods().forEach(method ->{
                    int nSmell=PmdRunner.collectCodeSmellMetricsClass(classResult.getClassName(),sourcePath.toString(),method.getStartLine(),method.getStartLine()+method.getLoc());
                    processMethod(method, filled_class, changedMethod, release, methodInstanceResults, nSmell);
                }
        );
    }

    private void processMethod(CKMethodResult method, ClassFile filled_class, List<MethodInstance> methodChanged,
                               Release release, Map<String,MethodInstance> methodInstanceResults, int nSmell) {

        boolean check=false;
        String methodName="anonymous";
        for(MethodInstance methodInstance: methodChanged){
            if(method.getMethodName().contains(methodInstance.getMethodName())
                    && filled_class.getPath().equals(methodInstance.getFilePath())
            ){
                methodName=methodInstance.getMethodName();
                check=true;
                break;
            }
        }
        if (!check) return;

        try {
            MethodInstance methodInstance = createMethodInstance(method, filled_class, methodName, release, nSmell);

            methodInstanceResults.put(MethodInstance.createMethodKey(methodInstance), methodInstance);
        } catch (Exception e) {
            LOGGER.error("Errore durante l'analisi del metodo: {} - {}",
                    method.getQualifiedMethodName(), e.getMessage(), e);
        }
    }

    private MethodInstance createMethodInstance(CKMethodResult method, ClassFile filled_class ,
                                                String methodName, Release release, int nSmell) {

        MethodInstance methodInstance = new MethodInstance();
        methodInstance.setFilePath(filled_class.getPath());
        methodInstance.setMethodName(methodName);
        methodInstance.setRelease(release);

        // Imposta le metriche
        setMethodMetrics(methodInstance, method, filled_class,nSmell);

        filled_class.addMethod(methodInstance);
        return methodInstance;
    }
    private void setMethodMetrics(MethodInstance methodInstance, CKMethodResult method, ClassFile filled_class, int nSmell) {
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
        methodInstance.setAge(filled_class.getAge());
        methodInstance.setnAuth(filled_class.getnAuth());
        methodInstance.setNr(filled_class.getNR());

        methodInstance.setBuggy(false);
    }


    Map<RevCommit, List<MethodInstance>> changedMethods=new HashMap<>();
    List<MethodInstance> fillMethodsBuggy(RevCommit commit){
        if(changedMethods.get(commit)==null){
            List<MethodInstance> methodsChanged = gitHubInfoRetrieve.getChangedMethodInstances(commit);
            changedMethods.put(commit,methodsChanged);
        }
        return changedMethods.get(commit);
    }

    private void assignBuggyness(ReleaseData data) {
        if (!resultsChanged) return;
        resultsChanged = false;

        LOGGER.info("Inizializzazione assegnazione buggyness");

        // Reset buggyness per tutti i metodi
        data.releaseResults.values().forEach(method -> method.setBuggy(false));

        // Se non ci sono ticket, termina
        if (data.releaseTickets == null || data.releaseTickets.isEmpty()) {
            LOGGER.info("Nessun ticket trovato per questa release");
            return;
        }

        // Crea indice per metodi per release
        Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease = new HashMap<>();
        data.releaseResults.values().forEach(method -> {
            if (method.getRelease() != null) {
                int releaseId = method.getRelease().getId();
                String methodKey = method.getFilePath() + "#" + method.getMethodName();
                methodsByRelease
                        .computeIfAbsent(releaseId, k -> new HashMap<>())
                        .computeIfAbsent(methodKey, k -> new ArrayList<>())
                        .add(method);
            }
        });

        // Processa i ticket
        for (Ticket ticket : data.releaseTickets) {
            Release checkInj = ticket.getIv() != null ? ticket.getIv() : ticket.getCalculatedIv();
            if (checkInj == null ) {
                continue;
            }
            processTicketChanges(ticket, methodsByRelease, data);
        }

        LOGGER.info("Completata assegnazione buggyness");
    }

    private void processTicketChanges(Ticket ticket,
                                      Map<Integer, Map<String, List<MethodInstance>>> methodsByRelease,
                                      ReleaseData data) {
        Release injected = ticket.getIv() != null ? ticket.getIv() : ticket.getCalculatedIv();
        Release fixed = ticket.getFv();

        for (RevCommit commit : getSortedCommit(ticket.getAssociatedCommits())) {
            String commitHash = commit.getId().getName();

            // Ottieni i metodi modificati dal commit
            List<MethodInstance> methodsChanged = fillMethodsBuggy(commit);
            Map<String, MethodInstance> commitMethods = resultCommitsMethods.get(commitHash);

            if (commitMethods == null || methodsChanged.isEmpty()) {
                continue;
            }

            // Crea set di metodi effettivamente modificati
            Set<String> modifiedMethodSignatures = new HashSet<>();
            for (MethodInstance changedMethod : methodsChanged) {
                for (MethodInstance commitMethod : commitMethods.values()) {
                    if (commitMethod.getMethodName().equals(changedMethod.getMethodName()) && commitMethod.getFilePath().equals(changedMethod.getFilePath())) {
                        modifiedMethodSignatures.add(commitMethod.getFilePath() + "#" + commitMethod.getMethodName());
                    }
                }
            }

            // Aggiorna buggyness per le release interessate
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
        // Per ogni release nel range
        for (int releaseId = injectedId; releaseId < fixedId; releaseId++) {
            Map<String, List<MethodInstance>> releaseMethods = methodsByRelease.get(releaseId);
            if (releaseMethods != null) {
                // Aggiorna solo i metodi modificati
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
            return a.getCommitterIdent().getWhen().compareTo(b.getCommitterIdent().getWhen());
        }
    }
}
