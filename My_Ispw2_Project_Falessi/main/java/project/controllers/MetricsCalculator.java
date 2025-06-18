
package project.controllers;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKMethodResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.LoggerFactory;
import project.models.ClassFile;
import project.models.MethodInstance;
import project.models.Release;
import project.models.Ticket;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static project.models.MethodInstance.ckSignature;
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

        // Load the commit cache using the optimized method
        Caching.loadCommitCache(resultCommitsMethods, projectName);
    }
    /**
     * Data class to hold release processing information
     */
    public  class ReleaseData {
        Release release;
        ConcurrentMap<String, MethodInstance> releaseResults;
        Map<RevCommit, Release> mapCommitRelease;
        List<RevCommit> releaseCommits;
        Map<String, RevCommit> commitsAnalyzed;
        Set<String> commitHashesToProcess;
        Map<String, RevCommit> commitsByHash;
        List<Ticket> releaseTickets;
    }


      void getCommitsInCache(ReleaseData releaseData ) {
        for (RevCommit commit : releaseData.releaseCommits) {
            String commitHash = commit.getId().getName();
            releaseData.commitsByHash.put(commitHash, commit);
            if (!resultCommitsMethods.containsKey(commitHash)) {
                releaseData.commitHashesToProcess.add(commitHash);
            } else {
                // This commit is already in cache, use it directly
                Map<String, MethodInstance> commitMetrics = resultCommitsMethods.get(commitHash);
                // Update the release for each method
                for (MethodInstance result : commitMetrics.values()) {
                    Release cur_release = releaseData.mapCommitRelease.get(commit);
                    if (cur_release != null) {
                        result.setRelease(cur_release);
                    } else {
                        result.setRelease(releaseData.release);
                    }
                }
                releaseData.releaseResults.putAll(commitMetrics);
                releaseData.commitsAnalyzed.put(commitHash, commit);
            }
        }
    }

    void processCommits(ReleaseData releaseData) throws IOException, ExecutionException, InterruptedException {
        System.out.println("processing the other commits ");
        // Crea un lock per sincronizzare l'accesso al repository
        Object threadLock = new Object();

        // Separa l'ultimo commit per elaborazione sequenziale
        AtomicInteger countThread = new AtomicInteger();

        // Contatore per gli errori di batch
        AtomicInteger batchErrorCount = new AtomicInteger(0);

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
        boolean restartProcessing = false;

        // Processa i commit in batch con possibilità di riavvio
        while (currentBatchIndex < commitHashList.size()) {
            // Reset del flag di riavvio
            restartProcessing = false;

            // Crea un nuovo thread pool per ogni ciclo di elaborazione
            // Questo permette di riavviare completamente l'elaborazione dopo un reset
            ForkJoinPool customThreadPool = new ForkJoinPool(numThreads);

            try {
                // Calcola l'indice di fine per il batch corrente
                int endIndex = Math.min(currentBatchIndex + batchSize, commitHashList.size());
                List<String> batchCommits = commitHashList.subList(currentBatchIndex, endIndex);

                LOGGER.info("Elaborazione batch di commit {}/{} (dimensione: {})", 
                        (currentBatchIndex/batchSize) + 1, 
                        (int) Math.ceil(commitHashList.size() / (double) batchSize),
                        batchCommits.size());

                // Contatore per gli errori in questo batch specifico
                AtomicInteger currentBatchErrors = new AtomicInteger(0);

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
                                        repositoryManager.checkoutRelease(commit, commitTempDir);
                                    }

                                    countThread.getAndIncrement();

                                    Map<String, MethodInstance> commitMetrics = calculateCKMetrics(commitTempDir, releaseData.release);
                                    for (MethodInstance result : commitMetrics.values()) {
                                        Release curRelease = releaseData.mapCommitRelease.get(commit);
                                        if (curRelease != null) {
                                            result.setRelease(curRelease);
                                        } else {
                                            result.setRelease(releaseData.release);
                                        }
                                    }

                                    resultCommitsMethods.put(commitHash, commitMetrics);
                                    // Aggiorna i risultati in modo thread-safe
                                    releaseData.releaseResults.putAll(commitMetrics);

                                    synchronized (threadLock) {
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
                                    // Incrementa i contatori di errore
                                    currentBatchErrors.incrementAndGet();
                                    batchErrorCount.incrementAndGet();
                                    // Tenta di liberare memoria
                                    System.gc();
                                } catch (Exception e) {
                                    LOGGER.error("Errore durante l'elaborazione del commit: {}", commitHash, e);
                                    // Incrementa i contatori di errore
                                    currentBatchErrors.incrementAndGet();
                                    batchErrorCount.incrementAndGet();
                                }
                            })
                        ).get(); // Attendi il completamento
                } catch (Exception e) {
                    LOGGER.error("Errore durante l'elaborazione del batch: {}", e.getMessage(), e);
                    // Incrementa il contatore degli errori di batch
                    batchErrorCount.incrementAndGet();

                    // Se ci sono troppi errori, esegui un reset completo
                    if (batchErrorCount.get() >= ERROR_THRESHOLD) {
                        LOGGER.warn("Troppi errori di batch ({}). Esecuzione reset completo...", batchErrorCount.get());

                        // Chiudi il thread pool corrente
                        customThreadPool.shutdown();

                        // Esegui il reset completo
                        restartProcessing = handleProcessingError(releaseData, true);

                        if (restartProcessing) {
                            // Reset del contatore degli errori di batch
                            batchErrorCount.set(0);

                            // Interrompi l'elaborazione corrente per riavviare
                            break;
                        }
                    }
                }

                // Se ci sono stati molti errori in questo batch, considera un reset
                if (currentBatchErrors.get() > batchSize / 2) {
                    LOGGER.warn("Molti errori nel batch corrente ({}/{}). Valutazione reset...", 
                            currentBatchErrors.get(), batchSize);

                    // Chiudi il thread pool corrente
                    customThreadPool.shutdown();

                    // Esegui il reset completo
                    restartProcessing = handleProcessingError(releaseData, true);

                    if (restartProcessing) {
                        // Reset del contatore degli errori di batch
                        batchErrorCount.set(0);

                        // Interrompi l'elaborazione corrente per riavviare
                        break;
                    }
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
        Caching.saveCommitCache(resultCommitsMethods, projectName);
        repositoryManager.restoreFromBackup();
        assignBuggyness(releaseData);
        // Notify callback with full results
        ClassWriter.writeResultsToFile(releaseData.release, projectName, releaseData.releaseResults, true);
    }

    // Contatore per tenere traccia degli errori significativi
    private AtomicInteger significantErrorCount = new AtomicInteger(0);

    // Soglia di errori oltre la quale consideriamo necessario un reset completo
    private static final int ERROR_THRESHOLD = 5;

    // Flag per indicare se è in corso un reset completo
    private volatile boolean resetInProgress = false;

    /**
     * Gestisce un errore durante l'elaborazione, con possibilità di reset completo
     * se il numero di errori supera una soglia.
     * 
     * @param releaseData I dati della release in elaborazione
     * @param forceReset Se true, forza un reset completo indipendentemente dal conteggio degli errori
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

            // Incrementa il contatore degli errori
            int currentErrorCount = significantErrorCount.incrementAndGet();

            // Determina se è necessario un reset completo
            boolean needsReset = forceReset || currentErrorCount >= ERROR_THRESHOLD;

            if (needsReset && !resetInProgress) {
                // Imposta il flag per evitare reset concorrenti
                resetInProgress = true;

                LOGGER.warn("Rilevati {} errori significativi. Esecuzione reset completo...", currentErrorCount);

                // Attendi un momento per permettere al sistema di stabilizzarsi
                Thread.sleep(2000);

                // Tenta di ripristinare il backup
                repositoryManager.restoreFromBackup();

                // Crea un nuovo backup per ripartire da uno stato pulito
                LOGGER.info("Creazione di un nuovo backup dopo il reset...");
                repositoryManager.backupRepository();

                // Salva lo stato corrente per non perdere il lavoro fatto finora
                if (!resultCommitsMethods.isEmpty()) {
                    LOGGER.info("Salvataggio dello stato corrente dopo il reset...");
                    Caching.saveCommitCache(resultCommitsMethods, projectName);
                }

                // Reset del contatore degli errori
                significantErrorCount.set(0);
                resetPerformed = true;

                // Reset completato
                resetInProgress = false;

                LOGGER.info("Reset completo eseguito con successo. Riavvio dell'elaborazione...");
            } else {
                // Gestione standard dell'errore senza reset completo
                LOGGER.info("Errore gestito senza reset completo (errori: {}/{})", currentErrorCount, ERROR_THRESHOLD);

                // Attendi un momento per permettere al sistema di stabilizzarsi
                Thread.sleep(10000);

                // Salva lo stato corrente per non perdere il lavoro fatto finora
                if (!resultCommitsMethods.isEmpty()) {
                    LOGGER.info("Salvataggio dello stato corrente dopo l'errore...");
                    Caching.saveCommitCache(resultCommitsMethods, projectName);
                }
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
        if ((log % ConstantSize.FREQUENCY_WRITE_CSV) == 0) {
            // Calculate buggyness for partial results
            assignBuggyness(releaseData);
            // Notify callback with partial results
            ClassWriter.writeResultsToFile(releaseData.release,projectName, releaseData.releaseResults,false);
        }

    }


    public void calculateReleaseMetrics(Release release, List<Ticket> releaseTickets) {
        // Utilizziamo ConcurrentHashMap per la thread-safety
        // it is an instance <key method method> because it continusely update methods during the commit
        // so when is present other method with same key upgrade the value in o(1) and not in o(n) if they will be in a set
        // the key is the signature of the metods
        ReleaseData data = new ReleaseData();
        data.release = release;
        data.releaseResults = new ConcurrentHashMap<>();
        data.releaseTickets = releaseTickets;
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

        // Reset del contatore degli errori significativi all'inizio di ogni release
        significantErrorCount.set(0);

        getCommitsInCache(data);
        int cachedCommitsSize = data.releaseCommits.size() - data.commitHashesToProcess.size();

        LOGGER.info("Found {} commits in cache, need to process {} commits",
                cachedCommitsSize,
                data.commitHashesToProcess.size());

        if (!data.releaseResults.isEmpty() && !data.commitHashesToProcess.isEmpty()) {
            System.out.println("writing before the elaboration");
            assignBuggyness(data);
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, false);
        }

        if (data.commitHashesToProcess.isEmpty()) {
            System.out.println("No commits to process for release ");
            assignBuggyness(data);
            // Notify callback with partial results
            ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, true);
            return;
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

                // Se arriviamo qui, l'elaborazione è stata completata con successo
                LOGGER.info("Elaborazione della release {} completata con successo", release.getName());
                break;

            } catch (IOException | ExecutionException e) {
                LOGGER.error("Errore durante l'elaborazione della release {}: {}", 
                        release.getName(), e.getMessage(), e);

                // Gestisci l'errore e determina se è necessario un nuovo tentativo
                boolean resetPerformed = handleProcessingError(data);

                if (!resetPerformed && attempt == maxRetries - 1) {
                    // Ultimo tentativo fallito senza reset, log di errore finale
                    LOGGER.error("Impossibile completare l'elaborazione della release {} dopo {} tentativi", 
                            release.getName(), maxRetries);

                    // Salva comunque i risultati parziali
                    assignBuggyness(data);
                    ClassWriter.writeResultsToFile(data.release, projectName, data.releaseResults, true);
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


    Map<RevCommit,Release> filterCommitsByRelease(Release targetRelease) {
        Map<RevCommit,Release> commitReleaseMap = new HashMap<>();

        // Itera su tutte le release fino alla release target
        for (Release currentRelease : releaseList) {
            // Ferma l'iterazione quando raggiungiamo la release target
            if (currentRelease.getId() >= targetRelease.getId()) {
                break;
            }

            // Per ogni commit nella release corrente
            for (RevCommit commit : currentRelease.getAllReleaseCommits()) {
                // Verifica che il commit sia effettivamente anteriore alla release target
                if (commit.getCommitterIdent().getWhen().before(targetRelease.getDate())) {
                    commitReleaseMap.put(commit, currentRelease);
                }else{
                    commitReleaseMap.put(commit, targetRelease);
                }
            }
        }

        return commitReleaseMap;
    }




    private Map<String, MethodInstance> calculateCKMetrics(Path sourcePath, Release release ){

        // Crea una mappa per contenere i risultati dei metodi analizzati


        Map<String,MethodInstance>methodInstanceResults=new HashMap<>();


        // Istanzia CK per l'analisi del codice
        CK ck = new CK();
        //final HashMap<Object, Integer> classFile_smell=(HashMap<Object, Integer>) PmdRunner.collectCodeSmellMetricsProject(sourcePath);;


        // Esegui l'analisi sui file sorgente specificati dal percorso
        ck.calculate(sourcePath, classResult -> {
            if (classResult.getMethods() == null || classResult.getMethods().isEmpty()) {
                // Nessun metodo nella classe -> ignora tranquillamente
                return;
            }
            // Itera su ciascun metodo trovato nella classe analizzata
            String pathClass=Release.normalizeToModuleAndClass(classResult.getClassName());

            int nSmell=-1;




            for (CKMethodResult method : classResult.getMethods()) {
                // Ottieni il nome completo del metodo (ad esempio: Classe.metodo)

                try {

                    nSmell=PmdRunner.collectCodeSmellMetricsClass(classResult.getClassName(),sourcePath.toString(),method.getStartLine(),method.getStartLine()+method.getLoc());




                    MethodInstance methodInstance= new MethodInstance();



                    methodInstance.setFilePath(method.getQualifiedMethodName());
                    methodInstance.setRelease(release);
                    methodInstance.setClassName(classResult.getClassName());
                    methodInstance.setMethodName(method.getMethodName());
                    // methods added to class files for assigment of buggyness


                    methodInstance.setLoc(method.getLoc());
                    methodInstance.setWmc(method.getWmc());
                    methodInstance.setQtyAssigment(method.getAssignmentsQty());
                    methodInstance.setQtyMathOperations(method.getMathOperationsQty());
                    methodInstance.setQtyTryCatch(method.getTryCatchQty());
                    methodInstance.setQtyReturn(method.getReturnQty());
                    methodInstance.setFanin(method.getFanin());
                    methodInstance.setFanout(method.getFanout());


                    ClassFile filled_class=release.findClassFileByApproxName(classResult.getClassName());
                    if (filled_class!=null){
                        methodInstance.setAge(filled_class.getAge());
                        methodInstance.setnAuth(filled_class.getnAuth());
                        methodInstance.setNr(filled_class.getNR());
                        filled_class.addMethod(methodInstance);
                        methodInstance.setClassName(filled_class.getPath());
                    }else{
                        methodInstance.setAge(-1);
                        methodInstance.setnAuth(-1);
                    }

                    methodInstance.setnSmells(nSmell);
                    methodInstance.setBuggy(false);
                    methodInstanceResults.put(MethodInstance.createMethodKey(methodInstance),methodInstance);

                } catch (Exception e) {
                    LOGGER.error("Errore durante l'analisi del metodo: {} ", method.getQualifiedMethodName(), e);

                }
            }

        });

        // Restituisci tutti i risultati analizzati
        return methodInstanceResults;
    }



    //questo metodo scorre le release e assegna il valore buggyness delle classi
    void assignBuggyness(ReleaseData data){
        List<ClassFile> buggyClasses = new ArrayList<>();
        LOGGER.info("Assign buggyness");
        List<RevCommit> revCommitList = new ArrayList<>();

        // Check if there are any tickets for this release
        if (data.releaseTickets == null || data.releaseTickets.isEmpty()) {
            LOGGER.info("No tickets found for this release. No methods will be marked as buggy.");

            // Ensure all methods in all classes have their buggy flag set to false
            for (ClassFile classFile : data.release.getReleaseAllClass()) {
                for (MethodInstance method : classFile.getMethods()) {
                    method.setBuggy(false);
                }
            }

            // Also ensure all methods in releaseResults have their buggy flag set to false
            for (MethodInstance method : data.releaseResults.values()) {
                method.setBuggy(false);
            }

            LOGGER.info("end buggyness association\n buggyness Class : 0");
            return;
        }

        for(Ticket ticket:data.releaseTickets){
            List<RevCommit> ticketCommits = ticket.getAssociatedCommits();
            revCommitList.addAll(ticketCommits);
        }

        // If there are no commits associated with tickets, no methods should be marked as buggy
        if (revCommitList.isEmpty()) {
            LOGGER.info("No commits associated with tickets for this release. No methods will be marked as buggy.");

            // Ensure all methods in all classes have their buggy flag set to false
            for (ClassFile classFile : data.release.getReleaseAllClass()) {
                for (MethodInstance method : classFile.getMethods()) {
                    method.setBuggy(false);
                }
            }

            // Also ensure all methods in releaseResults have their buggy flag set to false
            for (MethodInstance method : data.releaseResults.values()) {
                method.setBuggy(false);
            }

            LOGGER.info("end buggyness association\n buggyness Class : 0");
            return;
        }

        sortCommits(revCommitList);
        gitHubInfoRetrieve.getUpdatedRepo();

        int len = revCommitList.size();
        for (int i = 1; i < len; i++){
            RevCommit commit = revCommitList.get(i);
            List<String> modifiedClasses = gitHubInfoRetrieve.getDifference(commit,false);

            if (!modifiedClasses.isEmpty()) {
                updateBuggyness(modifiedClasses,buggyClasses,data.release);
            }
        }

        gitHubInfoRetrieve.getUpdatedRepo();
        LOGGER.info("end buggyness association\n buggyness Class : {}",buggyClasses.size());

    }

    //questo metodo scorre tutti i file modificati da un commit correlato ad un ticket, quindi tali classi
    //si assumono buggy e quindi deve essere settato il parametro buggy a true
    private void updateBuggyness(List<String> allPaths, List<ClassFile> buggyClasses, Release release) {
        for (String path : allPaths) {
            ClassFile currentFile = release.getClassFileByPath(path);
            if (currentFile == null) continue;
            buggyClasses.add(currentFile);
            String oldContent = gitHubInfoRetrieve.getFileContentBefore(path); // da implementare
            String newContent = gitHubInfoRetrieve.getFileContentNow(path);    // da implementare

            Map<String, String> oldMethods = extractMethodBodiesByName(oldContent); // nome → corpo
            Map<String, String> newMethods = extractMethodBodiesByName(newContent);

            for (MethodInstance method : currentFile.getMethods()) {
                String name = ckSignature(method.getMethodName());

                if (!oldMethods.containsKey(name) || !newMethods.containsKey(name)) {
                    // Metodo aggiunto o rimosso → potenzialmente buggy
                    method.setBuggy(true);
                    continue;
                }

                String oldBody = oldMethods.get(name);
                String newBody = newMethods.get(name);

                if (!oldBody.equals(newBody)) {
                    method.setBuggy(true);
                }
            }

            buggyClasses.add(currentFile);
        }
    }

    private  Map<String, String> extractMethodBodiesByName(String source) {
        Map<String, String> methodMap = new HashMap<>();
        if (source == null || source.trim().isEmpty()) {
            return methodMap;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(source);

            cu.findAll(MethodDeclaration.class).forEach(m -> {
                String name = m.getNameAsString();
                String body = m.getBody().map(Object::toString).orElse("");
                methodMap.put(name, body);
            });
        } catch (ParseProblemException e) {
            try {
                String errorFilePath = System.getProperty("java.io.tmpdir") + "/parsing_error_" +
                        System.currentTimeMillis() + ".txt";
                Files.writeString(Paths.get(errorFilePath), source);
            } catch (IOException ioe) {
                LOGGER.error("errore durante  il tentativo di recupero l'estrazione dei metodi: {} errore generato {}", e.getClass().getName(),  e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.error("errore durante l'estrazione dei metodi: {} errore generato {}", e.getClass().getName(),  e.getMessage());
        }

        return methodMap;
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
