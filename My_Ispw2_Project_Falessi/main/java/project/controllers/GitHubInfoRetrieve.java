package project.controllers;

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
import org.eclipse.jgit.revwalk.RevWalk;
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
import java.util.*;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import project.utils.ConstantsWindowsFormat;

public class GitHubInfoRetrieve {
    private final Logger LOGGER = LoggerFactory.getLogger(GitHubInfoRetrieve.class);
    private Git git;
    private FileRepository repo;
    private static final String SUFFIX = ".java";
    private static final String PREFIX = "/test/";
    private String project;

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
            List<String> directoryNames = Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .toList();

            if (directoryNames.stream().anyMatch(name -> name.startsWith(project + "_backup"))) {

                if (Files.exists(currentDirPath)) {
                    // delete the directory openjpa
                    Files.walk(currentDirPath)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                    LOGGER.info("Deleted existing directory: {}", currentDirPath);
                }

                // Find the first backup directory
                Path firstBackupDir = Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith(project + "_backup"))
                        .findFirst()
                        .orElse(null);

                if (firstBackupDir != null) {
                    // Rename backup directory to original name
                    Files.move(firstBackupDir, currentDirPath);
                    LOGGER.info("Renamed backup directory to: " + currentDirPath);
                }

                // Delete all remaining backup directories with the same name pattern
                Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)
                        .filter(path -> Files.isDirectory(path))
                        .filter(path -> path.getFileName().toString().startsWith(project + "_backup"))
                        .forEach(path -> {
                            try {
                                Files.walk(path)
                                        .sorted(Comparator.reverseOrder())
                                        .map(Path::toFile)
                                        .forEach(java.io.File::delete);
                                LOGGER.info("Deleted remaining backup directory: " + path);
                            } catch (IOException e) {
                                LOGGER.error("Error deleting backup directory : {}", e.getMessage());
                            }
                        });
            }
        } finally{
            LOGGER.info("Finished checking for backup directories");
        }
    }

        public void getUpdatedRepo()  {
        LOGGER.info("getUpdatedRepo: Updating repository for project " + project);

        try {
            Path repoPath = null;

            // Check if there are any backup directories
            List<String> directoryNames = java.nio.file.Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)
                    .filter(path -> Files.isDirectory(path))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());

            LOGGER.debug("getUpdatedRepo: Found directories: {} ", directoryNames);

            if (directoryNames.stream().anyMatch(name -> name.startsWith(project + "_backup"))) {
                LOGGER.debug("getUpdatedRepo: Found backup directories for project {} ", project);

                // Find the first backup directory
                Path firstBackupDir = java.nio.file.Files.list(ConstantsWindowsFormat.REPO_CLONE_PATH)
                        .filter(path -> Files.isDirectory(path))
                        .filter(path -> path.getFileName().toString().startsWith(project + "_backup"))
                        .findFirst()
                        .orElse(null);

                if (firstBackupDir != null) {
                    LOGGER.debug("getUpdatedRepo: Using backup directory: " + firstBackupDir);

                    // Check if the backup directory contains a .git directory
                    Path gitDir = firstBackupDir.resolve(".git");
                    if (Files.exists(gitDir) && Files.isDirectory(gitDir)) {
                        repoPath = gitDir;
                    } else {
                        LOGGER.debug("getUpdatedRepo: Backup directory does not contain a .git directory, using it as is");
                        repoPath = firstBackupDir;
                    }
                }
            }

            // If no backup directory was found or it doesn't contain a .git directory, use the original repository
            if (repoPath == null) {
                repoPath = ConstantsWindowsFormat.REPO_CLONE_PATH.resolve(project).resolve(".git");
                LOGGER.debug("getUpdatedRepo: Using original repository: " + repoPath);
            }

            // Check if the repository path exists
            if (!Files.exists(repoPath)) {
                LOGGER.debug("getUpdatedRepo: Repository path does not exist: " + repoPath);
                return;
            }

            // Try to open the repository
            try {
                this.repo = (FileRepository) new FileRepositoryBuilder()
                        .setGitDir(repoPath.toFile())
                        .build();
                this.git = new Git(this.repo);
                LOGGER.debug("getUpdatedRepo: Successfully opened repository: " + this.repo);
            } catch (Exception e) {
                LOGGER.error("getUpdatedRepo: Error opening repository: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.err.println("getUpdatedRepo: Error updating repository: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getPath() {
        Path outPath=ConstantsWindowsFormat.REPO_CLONE_PATH.resolve(this.project);
        return outPath.toString();
    }



    public void getMethodInstancesOfCommit(Release release) throws IOException {
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

                // Estrai i metodi dal file
                List<MethodInstance> methods = extractMethodsFromFile(fileContent, filePath);
                for (MethodInstance method : methods) {
                    release.addMethod(method);
                }
            }
        }
        treeWalk.close();
    }

    private List<MethodInstance> extractMethodsFromFile(String fileContent, String filePath) {
        List<MethodInstance> methods = new ArrayList<>();
        JavaParser javaParser = new JavaParser();

        // Parse the file content directly into a CompilationUnit
        CompilationUnit compilationUnit = javaParser.parse(fileContent).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Invalid Java code"));

        // Extract methods from the CompilationUnit
        compilationUnit.findAll(MethodDeclaration.class).forEach(methodDeclaration -> {
            // Use the constructor with parameters to create a MethodInstance instance
            MethodInstance methodInstance = new MethodInstance(filePath, methodDeclaration.getNameAsString(), methodDeclaration.getSignature().toString());
            methods.add(methodInstance);
        });
        return methods;
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
            Date commitDate = revCommit.getCommitterIdent().getWhen();
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
                Date currentCommitDate = revCommit.getCommitterIdent().getWhen();
                if (lastCommit == null || currentCommitDate.after(lastCommit.getCommitterIdent().getWhen())) {
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
                ClassFile classFile = new ClassFile(fileContent, filePath);
                release.addClassFile(classFile);
            }
        }
        treeWalk.close();

    }


    /**
     * Gets the content of a file before a specific commit
     * @param relativePath Path of the file
     * @return Content of the file before the commit
     */
    public String getFileContentBefore(String relativePath) {
        try {

            if (repo == null || relativePath == null) {
                LOGGER.error("getFileContentBefore: repo or relativePath is null");
                return "";
            }
           LOGGER.debug("getFileContentBefore - relativePath: " + relativePath+"\nrepo: "+repo+"\n\n");

            // Get the HEAD commit
            ObjectId headId = repo.resolve("HEAD");
            if (headId == null) {
                LOGGER.error("getFileContentBefore: HEAD is null");
                return "";
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit headCommit = revWalk.parseCommit(headId);
                if (headCommit.getParentCount() == 0) {
                    LOGGER.error("getFileContentBefore: No parent commit found");
                    return ""; // Nessun commit genitore
                }

                RevCommit parentCommit = revWalk.parseCommit(headCommit.getParent(0).getId());
                return getFileContentAtCommit(relativePath, parentCommit);
            }
        } catch (Exception e) {
            LOGGER.error("Error retrieving the content of the previous file: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for better debugging
            return "exception occurred";
        }
    }


    /**
     * Gets the content of a file in the current version
     * @param relativePath Path of the file
     * @return Content of the file in the current version
     */
    public String getFileContentNow(String relativePath) {
        try {

            if (repo == null || relativePath == null) {
                LOGGER.error("getFileContentNow: repo or relativePath is null");
                return "";
            }
            LOGGER.debug("getFileContentNow - relativePath: {} repo: {}", relativePath , repo );

            // Get the current HEAD commit
            ObjectId headId = repo.resolve("HEAD");
            if (headId == null) {
                LOGGER.error("getFileContentNow: HEAD is null");
                return "";
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit headCommit = revWalk.parseCommit(headId);
                return getFileContentAtCommit(relativePath, headCommit);
            }
        } catch (Exception e) {
            LOGGER.error("Error retrieving the content of the current file: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for better debugging
            return "exception occurred";
        }
    }

    /**
     * Ottiene il contenuto di un file a un commit specifico
     * @param path Percorso del file
     * @param commit Commit di riferimento
     * @return Contenuto del file al commit specificato
     */
    private String getFileContentAtCommit(String path, RevCommit commit) throws IOException {
        LOGGER.debug("getFileContentAtCommit - path: " + path + ", commit: " + commit.getName());

        if (commit == null) {
            LOGGER.error("getFileContentAtCommit: commit is null");
            return "";
        }

        if (commit.getTree() == null) {
            LOGGER.error("getFileContentAtCommit: commit tree is null");
            return "";
        }

        try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (treeWalk == null) {
                LOGGER.error("getFileContentAtCommit: TreeWalk is null for path: " + path);
                return "not founded val "; // File non trovato
            }

            ObjectId objectId = treeWalk.getObjectId(0);
            if (objectId == null) {
                LOGGER.error("getFileContentAtCommit: ObjectId is null for path: {}" , path);
                return ""; // ObjectId is null
            }

            ObjectLoader loader = repo.open(objectId);
            if (loader == null) {
                LOGGER.error ("getFileContentAtCommit: ObjectLoader is null for objectId: {} ",  objectId);
                return "";
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            loader.copyTo(output);

            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
             LOGGER.error ("Error in getFileContentAtCommit: {}" , e.getMessage());
            return "exception occurred";
        }
    }

}
