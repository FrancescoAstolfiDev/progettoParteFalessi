
package project.controllers;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKMethodResult;
import org.checkerframework.checker.units.qual.C;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.LoggerFactory;
import project.models.ClassFile;
import project.models.MethodInstance;
import project.models.Release;
import project.models.Ticket;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static project.models.MethodInstance.ckSignature;
import project.utils.ConstantSize;
import project.utils.ConstantsWindowsFormat;
import static org.apache.commons.io.FileUtils.deleteDirectory;

import org.slf4j.Logger;

public class MetricsCalculator {

    private final Logger LOGGER = LoggerFactory.getLogger(MetricsCalculator.class);
    private Git git;
    private Repository repository;
    private final Path tempDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "ck_analysis");
    private Release actRelease;
    private GitHubInfoRetrieve gitHubInfoRetrieve;
    private int max_last_classes;
    private String projectName;
    private String originalRepoPath;
    private String backupRepoPath;

    private Map <String,Map<String,MethodInstance>> resultCommitsMethods=new HashMap<>();
    private List<Ticket> releaseTickets;
    private final Path cacheDirPath = ConstantsWindowsFormat.cachePath;

    private Map <String,ClassFile> lastClassFiles=new HashMap<>();


    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve) throws IOException {
        this(gitHubInfoRetrieve, "default");
    }

    /**
     * Constructor that takes a GitHubInfoRetrieve object and a project name
     */
    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve, String projectName) throws IOException {
        File repoDir = new File(gitHubInfoRetrieve.getPath());
        this.repository = Git.open(repoDir).getRepository();
        this.git = new Git(repository);
        this.gitHubInfoRetrieve = gitHubInfoRetrieve;
        this.projectName = projectName;
        Files.createDirectories(Paths.get(String.valueOf(tempDirPath)));
        Files.createDirectories(cacheDirPath);

        // Load the commit cache using the optimized method
        Caching.loadCommitCache(resultCommitsMethods, projectName);
    }

    /**
     * Constructor that allows loading only specific commits from the cache
     */
    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve, Set<String> commitHashes) throws IOException {
        this(gitHubInfoRetrieve, commitHashes, "default");
    }

    /**
     * Constructor that allows loading only specific commits from the cache for a specific project
     */
    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve, Set<String> commitHashes, String projectName) throws IOException {
        File repoDir = new File(gitHubInfoRetrieve.getPath());
        this.repository = Git.open(repoDir).getRepository();
        this.git = new Git(repository);
        this.gitHubInfoRetrieve = gitHubInfoRetrieve;
        this.projectName = projectName;
        Files.createDirectories(Paths.get(String.valueOf(tempDirPath)));
        Files.createDirectories(cacheDirPath);

        // Load only the specified commits from the cache
        if (commitHashes != null && !commitHashes.isEmpty()) {
            LOGGER.info("Selectively loading " + commitHashes.size() + " commits from cache for project " + projectName);
            Caching.loadCommitCache(resultCommitsMethods, commitHashes, projectName);
        } else {
            // If no specific commits are requested, load all commits
            Caching.loadCommitCache(resultCommitsMethods, projectName);
        }
    }



    public Map<String, MethodInstance> calculateReleaseMetrics(List<RevCommit> commits, Release release, List<Ticket> releaseTickets) throws IOException, GitAPIException {
        // Utilizziamo ConcurrentHashMap per la thread-safety
            lastClassFiles.clear();
            ConcurrentMap<String, MethodInstance> releaseResults = new ConcurrentHashMap<>();
            List<RevCommit> passingList = filterCommitsByRelease(commits, release);
            int startIndex = Math.max(0, passingList.size()-ConstantSize.NUM_COMMITS);
            List<RevCommit>releaseCommits = commits.subList(startIndex, passingList.size());
            LOGGER.info((" \n\n inizio calcolo metriche per la release " + release.getName()));
            this.actRelease = release;
            this.releaseTickets = releaseTickets;
            File gitDir = repository.getDirectory();
            originalRepoPath = gitDir.getParentFile().getAbsolutePath();
            // Crea un percorso per il backup
            backupRepoPath = originalRepoPath + "_backup_" + System.currentTimeMillis();
            backupRepository();
            // Numero ottimale di thread basato sui core disponibili
            int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), ConstantSize.num_threads);
            LOGGER.info("Numero di thread: ", numThreads);
            ForkJoinPool customThreadPool = new ForkJoinPool(numThreads);

            try {
            LOGGER.info("number of commit to check: {}", releaseCommits.size());
            Map<String, RevCommit> commits_analized = new HashMap<>();
            // Crea un lock per sincronizzare l'accesso al repository
            Object repoLock = new Object();

            // Separa l'ultimo commit per elaborazione sequenziale
            AtomicInteger countThread = new AtomicInteger();
            // Elabora i commit in parallelo (escluso l'ultimo)
            sortCommits(releaseCommits);

            // First, identify which commits need to be processed and which are already in cache
            Set<String> commitHashesToProcess = new HashSet<>();
            Map<String, RevCommit> commitsByHash = new HashMap<>();

            for (RevCommit commit : releaseCommits) {
                String commitHash = commit.getId().getName();
                commitsByHash.put(commitHash, commit);

                if (!resultCommitsMethods.containsKey(commitHash)) {
                    commitHashesToProcess.add(commitHash);
                } else {
                    // This commit is already in cache, use it directly
                    Map<String, MethodInstance> commitMetrics = resultCommitsMethods.get(commitHash);
                    // Update the release for each method
                    for (MethodInstance result : commitMetrics.values()) {
                        result.setRelease(actRelease);
                    }
                    releaseResults.putAll(commitMetrics);
                    commits_analized.put(commitHash, commit);
                }
            }

            LOGGER.info("Found {} commits in cache, need to process {} commits",
                    (releaseCommits.size() - commitHashesToProcess.size()),
                    commitHashesToProcess.size());

            // Process only the commits that aren't in the cache
            if (!commitHashesToProcess.isEmpty()) {
                customThreadPool.submit(() ->
                        commitHashesToProcess.parallelStream().forEach(commitHash -> {
                            RevCommit commit = commitsByHash.get(commitHash);
                            Path commitTempDir = tempDirPath.resolve(release.getName() + "_" + commitHash);
                            commits_analized.put(commitHash, commit);
                            // Sincronizza l'accesso al repository Git
                            synchronized (repoLock) {
                                // Checkout del commit appartenente alla release
                                checkoutRelease(commit);
                                ensureTempDirectoryExists(commitTempDir);
                                exportCodeToDirectory(commit, commitTempDir);
                            }

                            // Il calcolo delle metriche CK può avvenire in parallelo
                            // (senza bisogno di sincronizzazione)
                            countThread.getAndIncrement();
                            int log = countThread.get();
                            if ((log % ConstantSize.frequency_log) == 0) {
                                int totalCommits = commitHashesToProcess.size();
                                int processedCommits = commits_analized.size();
                                int fromCache = releaseCommits.size() - totalCommits; // commits già in cache

                                LOGGER.info("\n\n  Thread {} in corso... analyzing commit {} ... commit analyzed {} ({} from cache, {} to process) \n\n",
                                        countThread.get(),
                                        commit.getId(),
                                        processedCommits,
                                        fromCache,
                                        totalCommits);

                            }
                            if ((log % ConstantSize.frequency_write_cache) == 0) {
                                Caching.saveCommitCache(resultCommitsMethods, projectName);
                            }

                            Map<String, MethodInstance> commitMetrics = calculateCKMetrics(commitTempDir);
                            resultCommitsMethods.put(commitHash, commitMetrics);
                            // Aggiorna i risultati in modo thread-safe
                            releaseResults.putAll(commitMetrics);

                            // Pulisci la directory temporanea del commit
                            cleanupTempDirectory(commitTempDir);

                        })
                ).get(); // Attendi il completamento
            }


            // processa l'instanziazione delle classi in modo sequenziale
            if (!releaseCommits.isEmpty()) {
                if(releaseTickets.isEmpty()){
                    restoreFromBackup();
                    return new HashMap<>(releaseResults);
                }
                max_last_classes=actRelease.getReleaseAllClass().size()/ConstantSize.MAX_BUGGY_PERCETAGE;
                max_last_classes=Math.max(max_last_classes,1);
                int lastIndex=releaseCommits.size() ;
                while(lastClassFiles.size()<max_last_classes && lastIndex>0){
                    long modified = 0;
                    Path commitTempDir = null;
                    String commitHash = "";
                    while (modified == 0 && lastIndex>0) {
                        lastIndex--;
                        RevCommit commit = releaseCommits.get(lastIndex);
                        commitHash = commit.getId().getName();
                        commitTempDir = tempDirPath.resolve(release.getName() + "_" + commitHash);
                        checkoutRelease(commit);
                        ensureTempDirectoryExists(commitTempDir);
                        modified = exportCodeToDirectory(commit, commitTempDir);

                    }
                    lastClassesEvaluation(commitTempDir);
                    cleanupTempDirectory(commitTempDir);
                }
            }



            LOGGER.info("Assegnazione buggyness " +lastClassFiles.size());
            assignBuggyness(ConstantSize.use_last_class);


            // Save the commit cache to disk
            Caching.saveCommitCache(resultCommitsMethods, projectName);
            return new HashMap<>(releaseResults);
        }catch (Exception e ){
            return new HashMap<>();
        }finally {
            customThreadPool.shutdown();
            restoreFromBackup();
        }
    }
    // Metodo di supporto per pulire le directory temporanee
    private void cleanupTempDirectory(Path dirPath) {
        try {
            if (Files.exists(dirPath)) {
                Files.walk(dirPath)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException e) {
            LOGGER.error("Impossibile pulire la directory temporanea " + dirPath + ": " + e.getMessage());
        }
    }
    private File ensureTempDirectoryExists(Path path) {
        try {
            Files.createDirectories(path);
            File dir = path.toFile();
            return dir;
        } catch (IOException e) {
            throw new RuntimeException("Impossibile creare la directory temporanea: " + path, e);
        }
    }
    /**
     * Crea una copia completa di backup del repository
     */
    private void backupRepository() throws IOException {
       LOGGER.info("Creazione backup completo del repository in: {} ", backupRepoPath);

        // Crea la directory di backup
        File backupDir = new File(backupRepoPath);
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Impossibile creare la directory di backup: " + backupRepoPath);
        }

        // Copia tutti i file, inclusa l'intera directory .git
        Path sourceDir = Paths.get(originalRepoPath);
        Files.walk(sourceDir)
            .forEach(source -> {
                try {
                    Path relativePath = sourceDir.relativize(source);
                    Path destination = Paths.get(backupRepoPath, relativePath.toString());

                    // Se è una directory, creala
                    if (Files.isDirectory(source)) {
                        if (!Files.exists(destination)) {
                            Files.createDirectories(destination);
                        }
                        return;
                    }

                    // Assicurati che la directory di destinazione esista
                    Files.createDirectories(destination.getParent());

                    // Copia il file
                    Files.copy(source, destination);
                } catch (IOException e) {
                    LOGGER.error("Errore durante il backup del file: {}, errore :{} ", source,  e.getMessage());
                }
            });
    }
    /**
     * Ripristina il repository dallo stato di backup
     */
    private void restoreFromBackup() throws IOException {
        if (backupRepoPath == null || originalRepoPath == null) {
            LOGGER.error("Nessun backup disponibile per il ripristino");
            return;
        }

        // Chiudi il repository attuale per liberare tutte le risorse
        if (git != null) {
            git.close();
        }

        // Elimina la directory corrente del repository
        deleteDirectory(new File(originalRepoPath));

        // Ricrea la directory originale
        File originalDir = new File(originalRepoPath);
        if (!originalDir.exists() && !originalDir.mkdirs()) {
            throw new IOException("Impossibile ricreare la directory originale: " + originalRepoPath);
        }

        // Copia tutti i file dal backup
        Path backupDir = Paths.get(backupRepoPath);
        Files.walk(backupDir)
                .forEach(source -> {
                    try {
                        Path relativePath = backupDir.relativize(source);
                        Path destination = Paths.get(originalRepoPath, relativePath.toString());

                        // Se è una directory, creala
                        if (Files.isDirectory(source)) {
                            if (!Files.exists(destination)) {
                                Files.createDirectories(destination);
                            }
                            return;
                        }

                        // Assicurati che la directory di destinazione esista
                        Files.createDirectories(destination.getParent());

                        // Copia il file
                        Files.copy(source, destination);
                    } catch (IOException e) {
                       LOGGER.error("Errore durante il ripristino del file: {} errore: {}", source,  e.getMessage());
                    }
                });

        // Riapri il repository
        git = Git.open(new File(originalRepoPath));
        repository = git.getRepository();
        deleteDirectory(new File(backupRepoPath));
    }






    public void calculateAll(List<Release> releaseList) throws IOException {
        RevCommit veryFirstCommit = null;

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


    private List<RevCommit> filterCommitsByRelease(List<RevCommit> commits, Release release) {
        // Implementazione che filtra i commit in base alla data della release
        List<RevCommit> filteredCommits = new ArrayList<>();
        for (RevCommit commit : commits) {
            if (commit.getAuthorIdent().getWhen().before(release.getDate())) {
                filteredCommits.add(commit);
            }
        }
        return filteredCommits;
    }



    private void checkoutRelease(RevCommit commit) {
        try {
            git.checkout().setName(commit.getName()).call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }








    private long exportCodeToDirectory(RevCommit commit, Path targetDir) {
        try{
            RevWalk revWalk = new RevWalk(repository);
            RevCommit parent = commit.getParentCount() > 0 ? revWalk.parseCommit(commit.getParent(0).getId()) : null;

            DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
            df.setRepository(repository);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            List<DiffEntry> diffs = df.scan(parent == null ? null : parent.getTree(), commit.getTree());
            boolean filesExported = false;

            for (DiffEntry entry : diffs) {
                if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) continue; // Ignora file rimossi

                String path = entry.getNewPath();
                if (path.endsWith(".java") && ! isTestFile(path)) {
                    ObjectId objectId = commit.getTree().getId();

                    try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
                        if (treeWalk != null) {
                            byte[] content = repository.open(treeWalk.getObjectId(0)).getBytes();

                            Path targetFilePath = targetDir.resolve(path);
                            Files.createDirectories(targetFilePath.getParent());
                            Files.write(targetFilePath, content);
                            filesExported = true;
                        }
                    }
                }
            }

            // Stampiamo quanti file sono stati effettivamente esportati
            // Verifichiamo prima che la directory esista

            long  count = 0;
            if (Files.exists(targetDir)) {
                count = Files.walk(targetDir)
                        .filter(p -> p.toString().endsWith(".java"))
                        .count();

            }
            return count;
        } catch (Exception e) {
           LOGGER.error("Errore durante l'esportazione dei file del commit: {} ", commit.getName(), e);
           return 0;
        }

    }

    private boolean isTestFile(String path) {
        String lowerPath = path.toLowerCase();
        return lowerPath.contains("/test/") || lowerPath.contains("test") || lowerPath.contains("mock");
    }


    private Map<String, MethodInstance> calculateCKMetrics(Path sourcePath){

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



                    ClassFile filled_class=new ClassFile();
                    MethodInstance methodInstance= new MethodInstance();



                    methodInstance.setFilePath(method.getQualifiedMethodName());
                    methodInstance.setRelease(this.actRelease);
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


                    filled_class=actRelease.findClassFileByApproxName(classResult.getClassName());
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

    /**
     * Fill the last class files list
     */
    private void lastClassesEvaluation(Path sourcePath)  {
        Map<String,ClassFile> innerResults=new HashMap<>();

        // Istanzia CK per l'analisi del codice
        CK ck = new CK();
        // Esegui l'analisi sui file sorgente specificati dal percorso
        ck.calculate(sourcePath, classResult -> {
            if (classResult.getMethods() == null || classResult.getMethods().isEmpty()) {
                // Nessun metodo nella classe -> ignora tranquillamente
                return;
            }
            ClassFile filled_class;
            filled_class=actRelease.findClassFileByApproxName(classResult.getClassName());
            if (! innerResults.containsKey(filled_class.getPath())){
                ClassFile actClass;
                actClass=filled_class;
                innerResults.put(actClass.getPath(),actClass);
            }
        });
        if(innerResults.isEmpty()){
            return ;
        }
        for( ClassFile file: innerResults.values()){
            if(file.getMethods().isEmpty() || lastClassFiles.containsKey(file.getPath())){
                continue;
            }
            if(lastClassFiles.size()>max_last_classes){
                return;
            }
            lastClassFiles.put(file.getPath(),file);
        }
    }
    List<ClassFile> buggyClasses = new ArrayList<>();
    //questo metodo scorre le release e assegna il valore buggyness delle classi
    private void assignBuggyness(boolean useLastClasses){
        if(useLastClasses){
            LOGGER.info(" ATTIVATA ASSEGNAZIONE BUGGYNESS CON LE ULTIME CLASSI ");
        }
        List<RevCommit> revCommitList = new ArrayList<>();
        for(Ticket ticket:releaseTickets){

            List<RevCommit> ticketCommits = ticket.getAssociatedCommits();
            revCommitList.addAll(ticketCommits);

        }
        sortCommits(revCommitList);

        int len = revCommitList.size();
        for (int i = 1; i < len; i++){
            RevCommit commit = revCommitList.get(i);
            List<String> modifiedClasses = gitHubInfoRetrieve.getDifference(commit,false);



            if (!modifiedClasses.isEmpty()) {
                updateBuggyness(modifiedClasses,useLastClasses);
            }

        }
        LOGGER.info("Fine assegnazione buggyness alle classi");
        if(buggyClasses.size()>actRelease.getReleaseAllClass().size()/ConstantSize.MAX_BUGGY_PERCETAGE   && !useLastClasses)  {
            assignBuggyness(true);
        }
       LOGGER.info("Classi bugginese: "+buggyClasses.size());
    }

    //questo metodo scorre tutti i file modificati da un commit correlato ad un ticket, quindi tali classi
    //si assumono buggy e quindi deve essere settato il parametro buggy a true
    private void updateBuggyness(List<String> allPaths,boolean  use_last_classes) {
        for (String path : allPaths) {
            ClassFile currentFile = use_last_classes?lastClassFiles.get(path):actRelease.getClassFileByPath(path);
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
