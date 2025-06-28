package project.utils;


import project.models.MethodInstance;
import project.models.Release;
import project.statefull.BookkeeperEntry;
import project.statefull.OpenjpaEntry;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public enum Projects implements EntryProject{
    BOOKKEEPER(new BookkeeperEntry()),
    OPENJPA(new OpenjpaEntry());
    private EntryProject entryProject;
    Projects(EntryProject entryProject){
        this.entryProject=entryProject;
    }
    public static Projects fromString(String projectName) {
        for (Projects project : Projects.values()) {
            if (project.getProjectName().equalsIgnoreCase(projectName)) {
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
}