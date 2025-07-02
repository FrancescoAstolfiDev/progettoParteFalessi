package project.utils;


import project.models.MethodInstance;
import project.models.Release;
import project.models.Ticket;
import project.statefull.BookkeeperEntry;
import project.statefull.OpenjpaEntry;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public enum Projects implements EntryProject {
    BOOKKEEPER(new BookkeeperEntry()),
    OPENJPA(new OpenjpaEntry());

    private EntryProject entryProject;
    Projects(EntryProject entryProject){
        this.entryProject = entryProject;
    }
    Projects(){}
    public static Projects fromString(String projectName) {
        // Convert input to uppercase as required
        String upperCaseProjectName = projectName.toUpperCase();
        for (Projects project : Projects.values()) {
            // Compare with enum name directly since we've already converted to uppercase
            if (project.name().equals(upperCaseProjectName)) {
                return project;
            }
        }
        throw new IllegalArgumentException("Progetto non valido: " + projectName +
                ". Progetti validi: " + Arrays.toString(Projects.values()));
    }
    public double getSplit(){
        return entryProject.getSplit();
    }

    @Override
    public Path getRefactoredSourcePath() {
        return entryProject.getRefactoredSourcePath();
    }

    @Override
    public Path getRefactoredClassPath() {
        return entryProject.getRefactoredClassPath();
    }

    @Override
    public String getRefactoredReleaseName() {
        return entryProject.getRefactoredReleaseName();
    }

    @Override
    public List<MethodInstance> getInitializedRefactoredMethods() {
        return entryProject.getInitializedRefactoredMethods();
    }

    @Override
    public Map<String,MethodInstance> getFilledRefactoredMethods() {
        return entryProject.getFilledRefactoredMethods();
    }

    @Override
    public boolean isRefactoredMethodsFilled() {
        return entryProject.isRefactoredMethodsFilled();
    }

    @Override
    @SuppressWarnings("squid:S3066")
    public void setMethods(Map<String, MethodInstance> methods) {
        entryProject.setMethods(methods);
    }

    @Override
    public MethodInstance getMethodToRefactor() {
        return entryProject.getMethodToRefactor();
    }

    @Override
    public void setTickets(Release release, List<Ticket> tickets) {
         entryProject.setTickets(release,tickets);
    }

    @Override
    public Map<String,List<Ticket>> getTickets() {
        return entryProject.getTickets();
    }

    @Override
    @SuppressWarnings("squid:S3066")
    public void setRefactoredClass(List<Release> releaseList) {
        entryProject.setRefactoredClass(releaseList);
    }

    @Override
    public int getNumStepDataset() {
        return entryProject.getNumStepDataset();
    }


    @Override
    public String getProjectName(){
        return entryProject.getProjectName();
    }

    public boolean afterRefactoredRelease(Release release,List<Release> releaseList){
         int refactoredReleaseId;
         int idRelease=Release.getId(release.getName(),releaseList);
         refactoredReleaseId=Release.getId(this.entryProject.getRefactoredReleaseName(),releaseList);
         return idRelease>refactoredReleaseId;
    }

    public Release getRefactoredRelease(List<Release> releaseList){
        String nameReleaseRefactored=getRefactoredReleaseName() ;
        for(Release release:releaseList){
            if(release.getName().equals(nameReleaseRefactored)){
                return release;
            }
        }
        return null;
    }
    public static String getTrainProject(){
        return Projects.BOOKKEEPER.getProjectName();
    }
    public static String getTestProject(){
        return Projects.OPENJPA.getProjectName();
    }
    public static int getNumStepDatasetStatic() {
        String trainSet=getTrainProject();
        String testSet=getTestProject();
        int stepTrain = Projects.valueOf(trainSet.toUpperCase()).getNumStepDataset();
        int stepTest = Projects.valueOf(testSet.toUpperCase()).getNumStepDataset();
        if(stepTrain==0){
            throw new IllegalArgumentException("Progetto non valido: " + trainSet +
                    ". Progetti validi: " + Arrays.toString(Projects.values()));
        }
        if(stepTest==0){
            throw new IllegalArgumentException("Progetto non valido: " + trainSet +
                    ". Progetti validi: " + Arrays.toString(Projects.values()));
        }
        return Math.min(stepTrain,stepTest);
    }

}
