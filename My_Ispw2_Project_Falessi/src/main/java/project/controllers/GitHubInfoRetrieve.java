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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import project.models.ClassFile;
import project.models.Release;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class GitHubInfoRetrieve {

    private Git git;
    private FileRepository repo;
    private static final String SUFFIX = ".java";
    private static final String PREFIX = "/test/";

    public GitHubInfoRetrieve(String project) throws IOException {
        this.repo = new FileRepository("/Users/francescoastolfi/progetto-java/proggetti_clonati/"+project+"/.git");
        this.git = new Git(repo);
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

    private int getAddedLines(DiffFormatter diffFormatter, DiffEntry entry) throws IOException {

        int addedLines = 0;
        for(Edit edit : diffFormatter.toFileHeader(entry).toEditList()) {
            addedLines += edit.getEndA() - edit.getBeginA();

        }
        return addedLines;

    }

    private int getDeletedLines(DiffFormatter diffFormatter, DiffEntry entry) throws IOException {

        int deletedLines = 0;
        for(Edit edit : diffFormatter.toFileHeader(entry).toEditList()) {
            deletedLines += edit.getEndB() - edit.getBeginB();

        }
        return deletedLines;

    }

    public void computeAddedAndDeletedLinesList(Release release) throws IOException {

        for(RevCommit commit : release.getAllReleaseCommits()) {
            DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);

            RevCommit parent;
            try{
                parent = commit.getParent(0);
            }
            catch(Exception e){
                continue;
            }

            diffFormatter.setRepository(this.repo);
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);

            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());
            for (DiffEntry entry : diffs) {
                ClassFile file = release.getClassFileByPath(entry.getNewPath());
                addDeletAddedChurn(file,diffFormatter,entry);
            }
        }
    }

    private void addDeletAddedChurn(ClassFile file,DiffFormatter diffFormatter,DiffEntry entry) throws IOException {
        if (file != null) {

            int deleted = getDeletedLines(diffFormatter, entry);
            int added = getAddedLines(diffFormatter, entry);
            file.setAddedLines(added);
            file.setDeletedLines(deleted);
            int churn = Math.abs(added - deleted);
            file.setChurn(file.getChurn() + churn);
            if(file.getMaxChurn() < churn){
                file.setMaxChurn(churn);
            }
            if(file.getMaxLocAdded() < added){
                file.setMaxLocAdded(added);
            }
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

    public List<RevCommit> getAllCommits() throws GitAPIException, IOException {
        //lista di tutti i commit
        String defaultBranch = repo.getBranch(); // es: "main" o "master"
        String treeName = "refs/heads/" + defaultBranch;
        Iterable<RevCommit> allCommits = git.log().add(repo.resolve(treeName)).call();
        List<RevCommit> commitList = new ArrayList<>();
        for (RevCommit revCommit:allCommits){
            commitList.add(revCommit);
        }

        return commitList;
    }

    public void orderCommitsByReleaseDate(List<RevCommit> allCommits, List<Release> releasesList){

        int numRelease = releasesList.size();


        for (RevCommit revCommit: allCommits){
            Date commitDate = revCommit.getCommitterIdent().getWhen();

            for (int k = 0; k < numRelease; k++){

                Release currentRelease = releasesList.get(k);
                if(k == 0 && commitDate.before(currentRelease.getDate())){
                    currentRelease.addCommitToReleaseList(revCommit);
                    break;
                }
                if((k == numRelease-1 && commitDate.before(currentRelease.getDate())) || (commitDate.before(currentRelease.getDate()) &&
                        commitDate.after(releasesList.get(k-1).getDate()))){
                    currentRelease.addCommitToReleaseList(revCommit);
                }
            }
        }
        deleteUselessRelease(releasesList);

    }

    private void deleteUselessRelease(List<Release> releasesList){
        List<Release> toDelete = new ArrayList<>();
        for (Release r:releasesList){
            if (r.getAllReleaseCommits().isEmpty()){
                toDelete.add(r);
            }
        }

        for (Release release:toDelete){
            releasesList.remove(release);
        }
    }

    public void setReleaseLastCommit(List<Release> allRelease){
        for (Release release : allRelease) {

            List<RevCommit> releaseCommits = release.getAllReleaseCommits();
            RevCommit lastCommit = null;

            for (RevCommit revCommit : releaseCommits) {

                Date currentCommitDate = revCommit.getCommitterIdent().getWhen();
                if (lastCommit == null) {
                    lastCommit = revCommit;
                    continue;
                }
                if (currentCommitDate.after(lastCommit.getCommitterIdent().getWhen())) {
                    lastCommit = revCommit;
                }
            }
            release.setLastCommitPreRelease(lastCommit);

        }
    }

}