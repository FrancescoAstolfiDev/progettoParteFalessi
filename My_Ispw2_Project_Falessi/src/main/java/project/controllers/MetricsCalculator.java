
package project.controllers;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKMethodResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.DepthWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
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
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static project.models.MethodInstance.ckSignature;

public class MetricsCalculator {


    private Git git;
    private Repository repository;
    private final String tempDirPath = System.getProperty("java.io.tmpdir") + "/ck_analysis/";
    private String originalRepoPath;
    private String backupRepoPath;
    private Release actRelease;
    private GitHubInfoRetrieve gitHubInfoRetrieve;
    private Map <String,ClassFile> lastClassFiles=new HashMap<>();
    private Map <String,ClassFile>fullClassFiles=new HashMap<>();
    private Map <RevCommit,Map<String,MethodInstance>> resultCommitsMethods=new HashMap<>();
    private Map<String,MethodInstance> releaseMethods;
    private List<Ticket> releaseTickets;
    private Map<String,Release> releaseResults;


    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve) throws IOException {
        File repoDir = new File(gitHubInfoRetrieve.getPath());
        this.repository = Git.open(repoDir).getRepository();
        this.git = new Git(repository);
        this.gitHubInfoRetrieve=gitHubInfoRetrieve;
        Files.createDirectories(Paths.get(tempDirPath));

    }
    public Map<String, MethodInstance> calculateReleaseMetrics(List<RevCommit> commits, Release release, List<Ticket> releaseTickets) throws IOException, GitAPIException {
        // Utilizziamo ConcurrentHashMap per la thread-safety
        ConcurrentMap<String, MethodInstance> releaseResults = new ConcurrentHashMap<>();
        List<RevCommit> releaseCommits = filterCommitsByRelease(commits, release);
        System.out.println((" \n\n inizio calcolo metriche per la release " + release.getName()));

        this.actRelease = release;
        this.releaseTickets = releaseTickets;

        File gitDir = repository.getDirectory();
        originalRepoPath = gitDir.getParentFile().getAbsolutePath();
        // Crea un percorso per il backup
        backupRepoPath = originalRepoPath + "_backup_" + System.currentTimeMillis();
        backupRepository();

        // Numero ottimale di thread basato sui core disponibili
        int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), 6);
        System.out.println("Numero di thread: " + numThreads);
        ForkJoinPool customThreadPool = new ForkJoinPool(numThreads);
        System.out.println("number of commit to check"+releaseCommits.size());
        Map<String,RevCommit> commits_analized=new HashMap<>();
        try {
            // Crea un lock per sincronizzare l'accesso al repository
            Object repoLock = new Object();

            // Separa l'ultimo commit per elaborazione sequenziale
            int lastIndex = releaseCommits.size() - 1;
            List<RevCommit> parallelCommits = lastIndex >= 0 ?
                    releaseCommits.subList(0, lastIndex) :
                    Collections.emptyList();


//            List<RevCommit> parallelCommits = lastIndex >= 0 ?
//                    releaseCommits.subList(0, ) :
//                    Collections.emptyList();
            AtomicInteger countThread= new AtomicInteger();
            // Elabora i commit in parallelo (escluso l'ultimo)

            customThreadPool.submit(() ->
                    parallelCommits.parallelStream().forEach(commit -> {
                        // Directory unica per ogni commit usando l'hash del commit
                        String commitHash = commit.getId().getName();
                        if(resultCommitsMethods.containsKey(commit)){
                            Map<String, MethodInstance> commitMetrics = resultCommitsMethods.get(commit);
                            // Aggiorna i risultati in modo thread-safe
                            releaseResults.putAll(commitMetrics);
                            commits_analized.put(commitHash,commit);
                        }else{

                            String commitTempDir = tempDirPath + release.getName() + "_" + commitHash;
                            commits_analized.put(commitHash,commit);

                            try {

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
                                int log=countThread.get();
                                if( (log % 2) ==0){
                                    System.out.println("Thread " + countThread.get() + " in corso..."+ "analyzing commit " + commit.getId() + " ..." +"commit analyzed"+commits_analized.size());
                                }

                                Map<String, MethodInstance> commitMetrics = calculateCKMetrics(commitTempDir);
                                resultCommitsMethods.put(commit,commitMetrics);
                                // Aggiorna i risultati in modo thread-safe
                                releaseResults.putAll(commitMetrics);

                                // Pulisci la directory temporanea del commit
                                cleanupTempDirectory(commitTempDir);

                            } catch (Exception e) {
//                            System.err.println("Errore durante l'elaborazione del commit " + commitHash + ": " + e.getMessage());
//                            e.printStackTrace();
                            }
                        }
                    })
            ).get(); // Attendi il completamento

            // Processa l'ultimo commit sequenzialmente
            if (!releaseCommits.isEmpty()) {
                lastIndex++;
                long modified=0;
                String commitTempDir="";
                String commitHash="";
                while(modified==0){
                    lastIndex--;
                    RevCommit commit = releaseCommits.get(lastIndex);
                    commitHash = commit.getId().getName();
                    commitTempDir = tempDirPath + release.getName() + "_" + commitHash;
                    checkoutRelease(commit);
                    ensureTempDirectoryExists(commitTempDir);
                    modified= exportCodeToDirectory(commit, commitTempDir);
                }

                Map<String, MethodInstance> lastCommitMetrics = calculateCKMetrics(commitTempDir);
                releaseResults.putAll(lastCommitMetrics);

                // Pulisci anche la directory dell'ultimo commit
                cleanupTempDirectory(commitTempDir);
            }

        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Errore durante l'elaborazione parallela", e);
        } finally {
            customThreadPool.shutdown();
        }

//        System.out.println("Fine calcolo metriche per la release " + release.getName());

        releaseMethods = new HashMap<>(releaseResults);
        System.out.println("Assegnazione buggyness " + releaseResults.size());
        restoreFromBackup();
        assignBuggyness();
        System.out.println("Fine assegnazione buggyness  " + release.getName());


        return new HashMap<>(releaseResults);
    }
    // Metodo di supporto per pulire le directory temporanee
    private void cleanupTempDirectory(String dirPath) {
        try {
            File tempDir = new File(dirPath);
            if (tempDir.exists()) {
                Files.walk(tempDir.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException e) {
            System.err.println("Impossibile pulire la directory temporanea " + dirPath + ": " + e.getMessage());
        }
    }
    private File ensureTempDirectoryExists(String releaseName) {
        Path path = Paths.get(tempDirPath + releaseName);
        try {
            Files.createDirectories(path);
            File dir = path.toFile();
            return dir;
        } catch (IOException e) {
            throw new RuntimeException("Impossibile creare la directory temporanea: " + path, e);
        }
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



    private void checkoutRelease(RevCommit commit) throws GitAPIException, IOException {
        git.checkout().setName(commit.getName()).call();
    }

    /**
     * Ripristina il repository dallo stato di backup
     */
    private void restoreFromBackup() throws IOException {
        if (backupRepoPath == null || originalRepoPath == null) {
            System.err.println("Nessun backup disponibile per il ripristino");
            return;
        }

//        System.out.println("Ripristino del repository dal backup: " + backupRepoPath);

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
                        System.err.println("Errore durante il ripristino del file: " + source + " - " + e.getMessage());
                    }
                });

        // Riapri il repository
        git = Git.open(new File(originalRepoPath));
        repository = git.getRepository();

//        System.out.println("Ripristino del repository completato.");

        // Pulisci il backup
        deleteDirectory(new File(backupRepoPath));
    }


    /**
     * Crea una copia completa di backup del repository
     */
    private void backupRepository() throws IOException {
//        System.out.println("Creazione backup completo del repository in: " + backupRepoPath);

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
//                        System.err.println("Errore durante il backup del file: " + source + " - " + e.getMessage());
                    }
                });

//        System.out.println("Backup completo del repository completato.");
    }



    private long exportCodeToDirectory(RevCommit commit, String targetDir) throws IOException {
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
                        Path targetFilePath = Paths.get(targetDir, path);
                        Files.createDirectories(targetFilePath.getParent());
                        Files.write(targetFilePath, content);
                        filesExported = true;
                    }
                }
            }
        }

        // Stampiamo quanti file sono stati effettivamente esportati
        // Verifichiamo prima che la directory esista
        Path targetPath = Paths.get(targetDir);
        long  count = 0;
        if (Files.exists(targetPath)) {
            count = Files.walk(targetPath)
                    .filter(p -> p.toString().endsWith(".java"))
                    .count();
//            System.out.println("File .java modificati esportati in " + targetDir + ": " + count);
        } else {
//            System.out.println("Nessun file .java esportato in " + targetDir);
        }
        return count;
    }

    private boolean isTestFile(String path) {
        String lowerPath = path.toLowerCase();
        return lowerPath.contains("/test/") || lowerPath.contains("test") || lowerPath.contains("mock");
    }

    private Map<String, MethodInstance> calculateCKMetrics(String sourcePath) throws IOException {

        // Crea una mappa per contenere i risultati dei metodi analizzati


        Map<String,MethodInstance>methodInstanceResults=new HashMap<>();
        Map<String,ClassFile> innerResults=new HashMap<>();
        HashMap<Object, Object> evauatedClass=new HashMap<>();

        // Istanzia CK per l'analisi del codice
        CK ck = new CK();
        //final HashMap<Object, Integer> classFile_smell=(HashMap<Object, Integer>) PmdRunner.collectCodeSmellMetricsProject(sourcePath);;


        // Esegui l'analisi sui file sorgente specificati dal percorso
        ck.calculate(Paths.get(sourcePath), classResult -> {
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

                    nSmell=PmdRunner.collectCodeSmellMetricsClass(classResult.getClassName(),sourcePath,method.getStartLine(),method.getStartLine()+method.getLoc());
                    evauatedClass.put(pathClass,nSmell);


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



                    if (! innerResults.containsKey(filled_class.getPath())){
                        ClassFile actClass=new ClassFile();
                        actClass.setPath(filled_class.getPath());
                        innerResults.put(actClass.getPath(),actClass);
                    }

                    ClassFile actClass=innerResults.get(filled_class.getPath());
                    actClass.addMethod(methodInstance);



                } catch (Exception e) {
//                    System.out.println(e);

                }
            }



            lastClassFiles=innerResults;

        });

        // Restituisci tutti i risultati analizzati
        return methodInstanceResults;
    }

    List<ClassFile> buggyClasses = new ArrayList<>();
    //questo metodo scorre le release e assegna il valore buggyness delle classi
    private void assignBuggyness(){

        List<RevCommit> revCommitList = new ArrayList<>();
        for(Ticket ticket:releaseTickets){
//            System.out.println("Assegnando buggyness al ticket " + ticket.getKey());
            List<RevCommit> ticketCommits = ticket.getAssociatedCommits();
            for (RevCommit commit : ticketCommits) {
//                System.out.println("Assegnando buggyness al commit " + commit.getName());
                revCommitList.add(commit);
            }

        }
        sortCommits(revCommitList);

        int len = revCommitList.size();
        for (int i = 1; i < len; i++){
            RevCommit commit = revCommitList.get(i);
            List<String> modifiedClasses = gitHubInfoRetrieve.getDifference(commit,false);
            System.out.println("\n\nda modificare:");
            for (String className : modifiedClasses) {
                System.out.println(" - " + className);
            }

            if (!modifiedClasses.isEmpty()) {
                updateBuggyness(modifiedClasses);
            }

        }
        System.out.println("Fine assegnazione buggyness alle classi");
        System.out.println("Classi bugginese: "+buggyClasses.size());
//        for(ClassFile file: buggyClasses){
//            System.out.println(file.getPath());
//        }
    }

    //questo metodo scorre tutti i file modificati da un commit correlato ad un ticket, quindi tali classi
    //si assumono buggy e quindi deve essere settato il parametro buggy a true
    private void updateBuggyness(List<String> allPaths) {
//        System.out.println("Classi da modificare: " + allPaths.size());

        for (String path : allPaths) {
            //ClassFile currentFile = lastClassFiles.get(path);
            ClassFile currentFile = actRelease.getClassFileByPath(path);

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
    private static List<String> extractMethodSignatures(String javaSource) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaSource);
            return cu.findAll(MethodDeclaration.class).stream()
                    .map(m -> m.getSignature().toString())
                    .toList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }

    }
    private static Map<String, String> extractMethodBodiesByName(String source) {
        Map<String, String> methodMap = new HashMap<>();

        if (source == null || source.trim().isEmpty()) {
//            System.out.println("Sorgente vuota o nulla, impossibile estrarre i metodi");
            return methodMap;
        }

        // Optional: se il contenuto sembra troppo corto o non valido, evita il parsing
//        if (!source.contains("class") && !source.contains("interface") && !source.contains("enum")) {
//            System.out.println("Il contenuto non sembra un file Java completo, salto il parsing.");
//            return methodMap;
//        }

        try {
            CompilationUnit cu = StaticJavaParser.parse(source);

            cu.findAll(MethodDeclaration.class).forEach(m -> {
                String name = m.getNameAsString();
                String body = m.getBody().map(Object::toString).orElse("");
                methodMap.put(name, body);
            });
        } catch (ParseProblemException e) {
            System.out.println("Errore di parsing del codice sorgente: " + e.getMessage());
            System.out.println("Il codice sorgente contiene sintassi Java non valida e verrà ignorato");
            try {
                String errorFilePath = System.getProperty("java.io.tmpdir") + "/parsing_error_" +
                        System.currentTimeMillis() + ".txt";
                Files.writeString(Paths.get(errorFilePath), source);
//                System.out.println("Codice problematico salvato in: " + errorFilePath);
            } catch (IOException ioe) {
                // Ignora errori durante il salvataggio
            }
        } catch (Exception e) {
           System.out.println("Errore durante l'estrazione dei metodi: " + e.getClass().getName());
           System.out.println("Messaggio: " + e.getMessage());
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