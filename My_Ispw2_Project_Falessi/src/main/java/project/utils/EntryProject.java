package project.utils;

import project.models.MethodInstance;
import project.models.Release;
import project.models.Ticket;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface EntryProject {
    String getProjectName();
    double getSplit();
    Path getRefactoredSourcePath(); // this is where the directory with the refactor is located
    Path getRefactoredClassPath();  // this is where the refactored class are contained
    String getRefactoredReleaseName();
    List<MethodInstance> getInitializedRefactoredMethods();
    Map<String,MethodInstance> getFilledRefactoredMethods();
    boolean isRefactoredMethodsFilled();
    void setMethods(Map<String,MethodInstance> methods);
    MethodInstance getMethodToRefactor();
    void setTickets (Release release, List<Ticket> tickets);
    Map<String,List<Ticket>> getTickets();
    void setRefactoredClass(List<Release> releaseList);
    int getNumStepDataset();
}
