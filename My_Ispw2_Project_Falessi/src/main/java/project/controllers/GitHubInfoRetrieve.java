package project.controllers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import project.models.MethodInstance;
import project.models.Release;
import project.models.ClassFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import project.models.Ticket;

public class GitHubInfoRetrieve {

    private Git git;
    private FileRepository repo;
    private static final String SUFFIX = ".java";
    private static final String PREFIX = "/test/";
    private String project;

    public GitHubInfoRetrieve(String project) throws IOException {
        this.project = project;
        this.repo = new FileRepository("/Users/francescoastolfi/progetto-java/proggetti_clonati/" + project + "/.git");
        this.git = new Git(repo);
        this.project = project;
    }

    public String getPath() {
        return "/Users/francescoastolfi/progetto-java/proggetti_clonati/" + this.project;
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
        String defaultBranch = repo.getBranch(); // es: "main" o "master"
        String treeName = "refs/heads/" + defaultBranch;
        Iterable<RevCommit> allCommits = git.log().add(repo.resolve(treeName)).call();
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
     * Ottiene il contenuto di un file prima di un commit specifico
     * @param relativePath Percorso del file
     * @return Contenuto del file prima del commit
     */
    public String getFileContentBefore(String relativePath) {
        try {
            if (repo == null || relativePath == null) {
                return "";
            }
            System.out.println("relativePath: " + relativePath+"\nrepo: "+repo+"\n\n");

            // Ottieni il commit HEAD
            ObjectId headId = repo.resolve("HEAD");
            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit headCommit = revWalk.parseCommit(headId);
                if (headCommit.getParentCount() == 0) {
                    return ""; // Nessun commit genitore
                }

                RevCommit parentCommit = revWalk.parseCommit(headCommit.getParent(0).getId());
                return getFileContentAtCommit(relativePath, parentCommit);
            }
        } catch (Exception e) {
            System.err.println("Errore nel recupero del contenuto del file precedente: " + e.getMessage());
            return "exception occurred";
        }
    }


    /**
     * Ottiene il contenuto di un file nella versione corrente
     * @param relativePath Percorso del file
     * @return Contenuto del file nella versione corrente
     */
    public String getFileContentNow(String relativePath) {
        try {
            if (repo == null || relativePath == null) {
                return "";
            }

            // Ottieni il commit HEAD corrente
            ObjectId headId = repo.resolve("HEAD");
            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit headCommit = revWalk.parseCommit(headId);
                return getFileContentAtCommit(relativePath, headCommit);
            }
        } catch (Exception e) {
            System.err.println("Errore nel recupero del contenuto del file attuale: " + e.getMessage());
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
        try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (treeWalk == null) {
                return "not founded val "; // File non trovato
            }

            ObjectId objectId = treeWalk.getObjectId(0);
            ObjectLoader loader = repo.open(objectId);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            loader.copyTo(output);

            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

}
