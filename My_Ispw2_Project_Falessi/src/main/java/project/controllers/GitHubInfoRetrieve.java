package project.controllers;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.models.MethodInstance;
import project.models.Release;
import project.models.ClassFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import project.statefull.ConstantsWindowsFormat;
import project.utils.CostumException;

public class GitHubInfoRetrieve {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubInfoRetrieve.class);
    private Git git;
    private FileRepository repo;
    private  static final String SUFFIX = ".java";
    private  static final String PREFIX = "/test/";
    private static final String BACKUP="_backup";
    private final String project;

    public GitHubInfoRetrieve(String project) throws IOException {

        this.project = project;
        initializingRepo();
        Path repoPath = ConstantsWindowsFormat.REPO_CLONE_PATH.resolve(project).resolve(".git");
        this.repo = (FileRepository) new FileRepositoryBuilder()
                .setGitDir(repoPath.toFile())
                .build();
        this.git = new Git(this.repo);
    }
    /*
    *   Make sure that if a directory with the backup name exists, remove the current folder
    *    and rename the backup folder
    *    So if inside cloned projects I have: openjpa and openjpa_backup
    *    1. Delete openjpa
    *    2. Rename the first folder found from openjpa_backup to openjpa
    *    3. Delete all remaining openjpa_backup folders
    */
    public void initializingRepo() throws IOException {
        try {
            Path currentDirPath = ConstantsWindowsFormat.REPO_CLONE_PATH.resolve(project);
            List<String> directoryNames;
            try (Stream<Path> pathStream = Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)) {
                directoryNames = pathStream
                        .filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .toList();
            }


            if (directoryNames.stream().anyMatch(name -> name.startsWith(project + BACKUP))) {

                if (Files.exists(currentDirPath)) {
                    // delete the directory openjpa
                    try (Stream<Path> pathStream = Files.walk(currentDirPath)) {
                        pathStream
                                .sorted(Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(java.io.File::delete);
                        LOGGER.info("Deleted existing directory: {}", currentDirPath);
                    }

                }

                // Find the first backup directory
                Path firstBackupDir ;
                try (Stream<Path> pathStream = Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)){
                    firstBackupDir = pathStream
                            .filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().startsWith(project + BACKUP))
                            .findFirst()
                            .orElse(null);
                } catch (IOException e) {
                    throw new CostumException("error when trying to move to backup dir",e);
                }

                if (firstBackupDir != null) {
                    // Rename backup directory to original name
                    Files.move(firstBackupDir, currentDirPath, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Renamed backup directory to: {} ", currentDirPath);
                }

                // Delete all remaining backup directories with the same name pattern
                try (Stream<Path> paths = Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)) {
                    paths.filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().startsWith(project + BACKUP))
                            .forEach(path -> {
                                try (Stream<Path> walkStream = Files.walk(path)) {
                                    walkStream.sorted(Comparator.reverseOrder())
                                            .forEach(p -> {
                                                try {
                                                    Files.delete(p);
                                                } catch (IOException e) {
                                                    LOGGER.error("Error deleting path: {}", e.getMessage());
                                                }
                                            });
                                    LOGGER.info("Deleted remaining backup directory: {} ", path);
                                } catch (IOException e) {
                                    LOGGER.error("Error deleting backup directory : {}", e.getMessage());
                                }
                            });
                }


            }
        } finally{
            LOGGER.info("Finished checking for backup directories");
        }
    }


    public String getPath() {
        Path outPath=ConstantsWindowsFormat.REPO_CLONE_PATH.resolve(this.project);
        return outPath.toString();
    }


    private List<MethodInstance> extractMethodsFromFile(String fileContent, String filePath, String className) {
        List<MethodInstance> methods = new ArrayList<>();
        JavaParser javaParser = new JavaParser();

        CompilationUnit cu = javaParser.parse(fileContent).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Invalid Java code"));

        // Cerca tutti i metodi nel file, tenendo traccia della gerarchia delle classi
        cu.findAll(MethodDeclaration.class).forEach(methodDecl -> {
            String containingClassName = getContainingClassName(methodDecl);
            String fullClassName = className != null ? className : containingClassName;

            MethodInstance methodInstance = new MethodInstance();
            methodInstance.setClassPath(filePath);
            methodInstance.setClassName(fullClassName);
            methodInstance.setMethodName(methodDecl.getNameAsString());
            methodInstance.setSignature(methodDecl.getSignature().toString());

            methods.add(methodInstance);
        });

        return methods;
    }
    private String getContainingClassName(MethodDeclaration methodDecl) {
        List<String> classNames = new ArrayList<>();

        methodDecl.walk(Node.TreeTraversal.PARENTS, node -> {
            if (node instanceof ClassOrInterfaceDeclaration classDecl) {
                classNames.add(0, classDecl.getNameAsString());
            }
        });
        return String.join("$", classNames);
    }





    public List<RevCommit> getAllCommits() throws GitAPIException, IOException {
        ObjectId head = repo.resolve("HEAD");
        if (head == null) {
            throw new IOException("Unable to resolve HEAD");
        }

        Iterable<RevCommit> allCommits = git.log().add(head).call();
        List<RevCommit> commitList = new ArrayList<>();
        for (RevCommit revCommit : allCommits) {
            commitList.add(revCommit);
        }
        return commitList;
    }

    public void orderCommitsByReleaseDate(List<RevCommit> allCommits, List<Release> releasesList) {
        int numRelease = releasesList.size();
        for (RevCommit revCommit : allCommits) {
            Date commitDate = Date.from(revCommit.getCommitterIdent().getWhenAsInstant());
            for (int k = 0; k < numRelease; k++) {
                Release currentRelease = releasesList.get(k);
                if (k == 0 && commitDate.before(currentRelease.getDate())) {
                    currentRelease.addCommitToReleaseList(revCommit);
                    break;
                }
                if ((k == numRelease - 1 && commitDate.before(currentRelease.getDate())) ||
                        (commitDate.before(currentRelease.getDate()) && commitDate.after(releasesList.get(k - 1).getDate()))) {
                    currentRelease.addCommitToReleaseList(revCommit);
                }
            }
        }
        deleteUselessRelease(releasesList);
    }

    private void deleteUselessRelease(List<Release> releasesList) {
        List<Release> toDelete = new ArrayList<>();
        for (Release r : releasesList) {
            if (r.getAllReleaseCommits().isEmpty()) {
                toDelete.add(r);
            }
        }
        releasesList.removeAll(toDelete);
    }

    public void setReleaseLastCommit(List<Release> allRelease) {
        for (Release release : allRelease) {
            List<RevCommit> releaseCommits = release.getAllReleaseCommits();
            RevCommit lastCommit = null;
            for (RevCommit revCommit : releaseCommits) {
                Date currentCommitDate = Date.from(revCommit.getCommitterIdent().getWhenAsInstant());
                if (lastCommit == null || currentCommitDate.after(Date.from(lastCommit.getCommitterIdent().getWhenAsInstant()))) {
                    lastCommit = revCommit;
                }
            }
            release.setLastCommitPreRelease(lastCommit);
        }
    }








    public List<String> getDifference(RevCommit commit,boolean searchAdded){
        RevCommit parent;
        try{
            parent = commit.getParent(0);
        }
        catch(Exception e){
            return Collections.emptyList();
        }

        List<String> allModifiedClass = new ArrayList<>();

        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repo);
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);

            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());
            getModifiedClasses(searchAdded,diffs,allModifiedClass);
        } catch (IOException e) {
            //IGNORO QUESTO CASO
        }
        return allModifiedClass;
    }
    private void getModifiedClasses(boolean searchAdded,List<DiffEntry> diffs,List<String> allModifiedClass){
        if(searchAdded){
            for (DiffEntry diff : diffs) {
                String path = diff.getNewPath();
                if (diff.getChangeType() == DiffEntry.ChangeType.ADD && path.contains(SUFFIX) && !path.contains(PREFIX)) {
                    allModifiedClass.add(path);
                }
            }
        }
        else{
            for (DiffEntry diff : diffs) {
                String path = diff.getNewPath();
                if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY && path.contains(SUFFIX) && !path.contains(PREFIX)) {
                    allModifiedClass.add(path);
                }
            }
        }
    }

    public void getClassFilesOfCommit(Release release) throws IOException {
        TreeWalk treeWalk = new TreeWalk(repo);
        RevCommit commit = release.getLastCommitPreRelease();
        RevTree tree = commit.getTree();
        treeWalk.addTree(tree);
        treeWalk.setRecursive(true);

        while (treeWalk.next()) {
            String filePath = treeWalk.getPathString();

            if (filePath.contains(SUFFIX) && !filePath.contains(PREFIX)) {
                ObjectId objectId = treeWalk.getObjectId(0);
                ObjectLoader loader = null;
                try {
                    loader = repo.open(objectId);
                } catch (MissingObjectException e) {
                    continue;
                }

                byte[] fileContentBytes = loader.getBytes();
                String fileContent = new String(fileContentBytes);

                // Analizza il contenuto per trovare tutte le classi e i metodi
                extractClassesAndMethods(fileContent, filePath, release);
            }
        }
        treeWalk.close();
    }


    private void extractClassesAndMethods(String fileContent, String filePath, Release release) {
        JavaParser javaParser = new JavaParser();
        CompilationUnit cu = javaParser.parse(fileContent).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Invalid Java code"));

        // Estrai tutte le classi (principali e annidate)
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = getFullClassName(classDecl);

            // Crea un nuovo ClassFile per ogni classe (principale o annidata)
            ClassFile classFile = new ClassFile(fileContent, filePath);
            classFile.setClassName(className); // Aggiungiamo il nome completo della classe

            // Estrai i metodi della classe
            classDecl.getMethods().forEach(methodDecl -> {
                MethodInstance methodInstance = new MethodInstance();
                methodInstance.setClassPath(filePath);
                methodInstance.setClassName(className); // Usa il nome completo della classe
                methodInstance.setMethodName(methodDecl.getNameAsString());
                methodInstance.setSignature(methodDecl.getSignature().toString());
                methodInstance.setReleaseName(release.getName());

                // Aggiungi il metodo alla classe
                classFile.addMethod(methodInstance);
            });

            // Aggiungi la classe alla release
            release.addClassFile(classFile);
        });
    }

    private String getFullClassName(ClassOrInterfaceDeclaration classDecl) {
        // Ottieni il nome completo della classe includendo le classi annidate
        List<String> classNames = new ArrayList<>();
        classNames.add(classDecl.getNameAsString());

        // Risali l'albero dei nodi per trovare le classi contenitori
        classDecl.walk(Node.TreeTraversal.PARENTS, node -> {
            if (node instanceof ClassOrInterfaceDeclaration parentClass) {
                classNames.add(0, parentClass.getNameAsString());
            }
        });

        return String.join("$", classNames);
    }


    /**
     * Ottiene il contenuto di un file a un commit specifico
     * @param path Percorso del file
     * @param commit Commit di riferimento
     * @return Contenuto del file al commit specificato
     */
    private String getFileContentAtCommit(String path, RevCommit commit) throws IOException {
        if (commit == null) {
            LOGGER.error("getFileContentAtCommit: commit is null");
            return "";
        }
        LOGGER.debug("getFileContentAtCommit - path: {} commit: {} " , path , commit.getName());
        if (commit.getTree() == null) {
            LOGGER.error("getFileContentAtCommit: commit tree is null");
            return "";
        }

        try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (treeWalk == null) {
                LOGGER.error("getFileContentAtCommit: TreeWalk is null for path: {}" , path);
                return "not founded val "; // File non trovato
            }

            ObjectId objectId = treeWalk.getObjectId(0);
            if (objectId == null) {
                LOGGER.error("getFileContentAtCommit: ObjectId is null for path: {}" , path);
                return ""; // ObjectId is null
            }

            ObjectLoader loader = repo.open(objectId);


            ByteArrayOutputStream output = new ByteArrayOutputStream();
            loader.copyTo(output);

            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
             LOGGER.error ("Error in getFileContentAtCommit: {}" , e.getMessage());
            return "exception occurred";
        }
    }
    /**
     * Recupera i metodi modificati o cancellati in un commit specifico
     * @param commit Il commit da analizzare
     * @return Lista di MethodInstance modificate o cancellate
     */
    public List<MethodInstance> getChangedMethodInstances(RevCommit commit) {
        List<MethodInstance> changedMethods = new ArrayList<>();
        RevCommit parent = getParentCommit(commit);
        if (parent == null) return changedMethods;

        try (DiffFormatter diffFormatter = setupDiffFormatter()) {
            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());

            for (DiffEntry diff : diffs) {
                if (isRelevantJavaFile(diff)) {
                    String oldContent = getFileContentAtCommit(diff.getOldPath(), parent);

                    if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                        // Per i file cancellati, estrai tutti i metodi come modificati
                        changedMethods.addAll(extractMethodsFromFile(oldContent, diff.getOldPath(), null));
                    } else if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                        String newContent = getFileContentAtCommit(diff.getNewPath(), commit);
                        processModifiedFile(oldContent, newContent, diff.getOldPath(), changedMethods);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Errore nell'analisi delle modifiche del commit: {}", e.getMessage());
        }

        return changedMethods;
    }


    private RevCommit getParentCommit(RevCommit commit) {
    try {
        return commit.getParent(0);
    } catch (Exception e) {
        LOGGER.error("Nessun commit padre trovato per {}", commit.getName());
        return null;
    }
}

private DiffFormatter setupDiffFormatter() {
    DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
    diffFormatter.setRepository(repo);
    diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
    return diffFormatter;
}







    private void processModifiedFile(String oldContent, String newContent, String filePath, List<MethodInstance> changedMethods) {
        // Estrai i metodi da entrambe le versioni del file
        List<MethodInstance> oldMethods = extractMethodsFromFile(oldContent, filePath, null);
        List<MethodInstance> newMethods = extractMethodsFromFile(newContent, filePath, null);

        // Confronta i metodi per trovare quelli modificati o rimossi
        for (MethodInstance oldMethod : oldMethods) {
            boolean found = false;
            for (MethodInstance newMethod : newMethods) {
                if (isSameMethod(oldMethod, newMethod)) {
                    found = true;
                    if (!oldContent.equals(newContent)) {
                        // Il metodo è stato modificato
                        changedMethods.add(newMethod);
                    }
                    break;
                }
            }
            if (!found) {
                // Il metodo è stato rimosso
                changedMethods.add(oldMethod);
            }
        }
    }

    private boolean isSameMethod(MethodInstance method1, MethodInstance method2) {
        return method1.getClassName().equals(method2.getClassName()) &&
                method1.getMethodName().equals(method2.getMethodName()) &&
                method1.getSignature().equals(method2.getSignature());
    }

    private boolean isRelevantJavaFile(DiffEntry diff) {
        return (diff.getChangeType() == DiffEntry.ChangeType.MODIFY ||
                diff.getChangeType() == DiffEntry.ChangeType.DELETE) &&
                diff.getOldPath().endsWith(SUFFIX) &&
                !diff.getOldPath().contains(PREFIX);
    }




}
