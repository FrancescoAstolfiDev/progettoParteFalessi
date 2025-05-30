
package project.controllers;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKMethodResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
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
import project.utils.ConstantSize;
public class MetricsCalculator {


    private Git git;
    private Repository repository;
    private final Path tempDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "ck_analysis");
    private Release actRelease;
    private GitHubInfoRetrieve gitHubInfoRetrieve;



    private Map <RevCommit,Map<String,MethodInstance>> resultCommitsMethods=new HashMap<>();
    private List<Ticket> releaseTickets;


    private Map <String,ClassFile> lastClassFiles=new HashMap<>();
    private LinkedList<Map<String, ClassFile>> resultsInstances = new LinkedList<>();


    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve) throws IOException {
        File repoDir = new File(gitHubInfoRetrieve.getPath());
        this.repository = Git.open(repoDir).getRepository();
        this.git = new Git(repository);
        this.gitHubInfoRetrieve=gitHubInfoRetrieve;
        Files.createDirectories(Paths.get(String.valueOf(tempDirPath)));

    }
    public Map<String, MethodInstance> calculateReleaseMetrics(List<RevCommit> commits, Release release, List<Ticket> releaseTickets) throws IOException, GitAPIException {
        // Utilizziamo ConcurrentHashMap per la thread-safety
        try{


        ConcurrentMap<String, MethodInstance> releaseResults = new ConcurrentHashMap<>();
        List<RevCommit> passingList = filterCommitsByRelease(commits, release);
        int startIndex = Math.max(0, passingList.size()-ConstantSize.NUM_COMMITS);
        List<RevCommit>releaseCommits = commits.subList(startIndex, passingList.size());
        System.out.println((" \n\n inizio calcolo metriche per la release " + release.getName()));

        this.actRelease = release;
        this.releaseTickets = releaseTickets;

        // Numero ottimale di thread basato sui core disponibili
        int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), 6);
        System.out.println("Numero di thread: " + numThreads);
        ForkJoinPool customThreadPool = new ForkJoinPool(numThreads);
        System.out.println("number of commit to check" + releaseCommits.size());
        Map<String, RevCommit> commits_analized = new HashMap<>();
        try {
            // Crea un lock per sincronizzare l'accesso al repository
            Object repoLock = new Object();

            // Separa l'ultimo commit per elaborazione sequenziale
            AtomicInteger countThread = new AtomicInteger();
            // Elabora i commit in parallelo (escluso l'ultimo)
            sortCommits(releaseCommits);

            customThreadPool.submit(() ->
                releaseCommits.parallelStream().forEach(commit -> {
                    // Directory unica per ogni commit usando l'hash del commit
                    String commitHash = commit.getId().getName();
                    if (resultCommitsMethods.containsKey(commit)) {
                        Map<String, MethodInstance> commitMetrics = resultCommitsMethods.get(commit);
                        // Aggiorna i risultati in modo thread-safe
                        releaseResults.putAll(commitMetrics);
                        commits_analized.put(commitHash, commit);
                    } else {
                        Path commitTempDir = tempDirPath.resolve(release.getName() + "_" + commitHash);
                        commits_analized.put(commitHash, commit);
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
                            int log = countThread.get();
                            if ((log % 2) == 0) {
                                System.out.println("Thread " + countThread.get() + " in corso..." + "analyzing commit " + commit.getId() + " ..." + "commit analyzed" + commits_analized.size());
                            }

                            Map<String, MethodInstance> commitMetrics = calculateCKMetrics(commitTempDir);
                            resultCommitsMethods.put(commit, commitMetrics);
                            // Aggiorna i risultati in modo thread-safe
                            releaseResults.putAll(commitMetrics);

                            // Pulisci la directory temporanea del commit
                            cleanupTempDirectory(commitTempDir);

                        } catch (Exception e) {
//                            System.err.println("Errore durante l'elaborazione del commit " + commitHash + ": " + e.getMessage());
                           e.printStackTrace();
                           cleanupTempDirectory(commitTempDir);
                        }
                    }
                })
            ).get(); // Attendi il completamento


            // processa l'instanziazione delle classi in modo sequenziale
            if (!releaseCommits.isEmpty() && releaseTickets.size() > 0 ) {
                int lastIndex=releaseCommits.size() ;
                while(lastClassFiles.size()<ConstantSize.SIZE_WINDOW && lastIndex>0){
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

        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Errore durante l'elaborazione parallela", e);
        } finally {
            customThreadPool.shutdown();
        }

//        System.out.println("Fine calcolo metriche per la release " + release.getName());


        System.out.println("Assegnazione buggyness " +lastClassFiles.size());
      //  restoreFromBackup();
        assignBuggyness();
        System.out.println("Fine assegnazione buggyness  " + release.getName());
        return new HashMap<>(releaseResults);
    }catch (Exception e ){
        //restoreFromBackup();
        System.err.println("Errore durante l'assegnazione dei buggyness: " + e.getMessage());
        return new HashMap<>();
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
            System.err.println("Impossibile pulire la directory temporanea " + dirPath + ": " + e.getMessage());
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
        String targetCommit = commit.getId().getName();
        String currentCommit = repository.resolve("HEAD").getName();

        if (!currentCommit.equals(targetCommit)) {
            // Verifica e rimuovi eventuali file di lock
            File indexLock = new File(repository.getDirectory(), "index.lock");
            if (indexLock.exists()) {
                if (!indexLock.delete()) {
                    throw new IOException("Impossibile rimuovere il file index.lock");
                }
            }

            // Reset hard per pulire lo stato
            git.reset().setMode(ResetCommand.ResetType.HARD).call();

            // Usa setForced() invece di setForce()
            git.checkout()
                    .setName(targetCommit)
                    .setForced(true)
                    .call();

        }
    }








    private long exportCodeToDirectory(RevCommit commit, Path targetDir) throws IOException {
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


    private Map<String, MethodInstance> calculateCKMetrics(Path sourcePath) throws IOException {

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
//                    System.out.println(e);

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
                addLastClassFiles(innerResults);
            }





        });



    }



    private void addLastClassFiles(Map<String,ClassFile> lastResults){
        if (resultsInstances.size() >= ConstantSize.SIZE_WINDOW) {
            resultsInstances.removeFirst();
        }
        resultsInstances.addLast(lastResults);

        lastClassFiles.clear();
        for (Map<String, ClassFile> map : resultsInstances) {
            lastClassFiles.putAll(map);
        }
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
//        System.out.println("Classi da modificare: " + allPaths.sizeWindow());

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