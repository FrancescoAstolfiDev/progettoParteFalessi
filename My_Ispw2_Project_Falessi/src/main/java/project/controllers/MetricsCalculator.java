package project.controllers;

import org.eclipse.jgit.revwalk.RevCommit;
import project.models.ClassFile;
import project.models.Release;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.*;

import java.io.IOException;
import java.util.*;


public class MetricsCalculator {
    private GitHubInfoRetrieve gitHubInfoRetrieve;

    public MetricsCalculator(GitHubInfoRetrieve gitHubInfoRetrieve){
        this.gitHubInfoRetrieve = gitHubInfoRetrieve;
    }
    public void calculateAll(List<Release> releaseList) throws IOException {
        RevCommit veryFirstCommit = null;
        int len = releaseList.size();
        for(int i = 0; i < len; i++){
            Release currRelease = releaseList.get(i);

            List<ClassFile> classFiles = currRelease.getReleaseAllClass();
            for (ClassFile classFile: classFiles){
                calculateLineOfCode(classFile);
                int complexity = calculateCyclomaticComplexity(classFile.getContent());
                classFile.setCiclomaticComplexity(complexity);
            }

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

            gitHubInfoRetrieve.computeAddedAndDeletedLinesList(currRelease);

        }
        calculateAge(releaseList);
        calculateChurnAndLoc(releaseList);

    }

    private void creationDateSetter(List<ClassFile> classFiles,RevCommit firstCommit){
        for (ClassFile file : classFiles) {
            if (file.getCreationDate() == null) {
                file.setCreationDate(firstCommit.getCommitterIdent().getWhen());
            }
        }
    }

    private void calculateChurnAndLoc(List<Release> releaseList){
        int len = releaseList.size();
        for (int i = 0; i < len; i++){
            List<ClassFile> allFiles = releaseList.get(i).getReleaseAllClass();
            for(ClassFile file:allFiles){
                file.setLocAdded(file.getAddedLines());
                if(file.getNR() != 0){
                    file.setMeanChurn((file.getChurn() / file.getNR()));
                }
            }
        }
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

    public int calculateCyclomaticComplexity(String javaClass) {
        JavaParser javaParser = new JavaParser();
        ParseResult<CompilationUnit> parseResult = javaParser.parse(javaClass);
        CompilationUnit compilationUnit = parseResult.getResult().orElseThrow(() -> new IllegalArgumentException("Invalid Java code"));

        Set<Class<? extends Node>> controlFlowStatements = new HashSet<>(Arrays.asList(
                IfStmt.class,
                ForStmt.class,
                WhileStmt.class,
                DoStmt.class,
                SwitchStmt.class,
                ContinueStmt.class,
                BreakStmt.class,
                CatchClause.class));

        int controlFlowCount = compilationUnit.findAll(Node.class, node -> controlFlowStatements.contains(node.getClass())).size();

        return controlFlowCount + 1;
    }
    public void calculateLineOfCode(ClassFile classFile){
        String content = classFile.getContent();
        int totalLines = countLines(content);
        int codeLines = countCodeLines(content);
        classFile.setLineCodeNoComment(codeLines);
        classFile.setLineCodeComment(totalLines);
    }

    public static int countLines(String code) {
        return code.split("\\r?\\n").length;
    }

    public static int countCodeLines(String code) {
        String[] lines = code.split("\\r?\\n");
        int codeLines = 0;
        boolean inCommentBlock = false;

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("/*")) {
                inCommentBlock = true;
            }

            if (!inCommentBlock && !line.isEmpty()) {
                codeLines++;
            }

            if (line.endsWith("*/")) {
                inCommentBlock = false;
            }
        }

        return codeLines;
    }
}

