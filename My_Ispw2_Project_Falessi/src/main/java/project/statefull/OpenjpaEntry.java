package project.statefull;

import project.models.MethodInstance;
import project.utils.EntryProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class OpenjpaEntry implements EntryProject {
    private  List<MethodInstance> methods;
    private static boolean methods_setted=false;
    @Override
    public String getProjectName() {
        return "";
    }

    @Override
    public double getSplit() {
        return 0;
    }

    @Override
    public Path getRefactoredSourcePath() {
        return null;
    }

    @Override
    public Path getRefactoredClassPath() {
        return null;
    }

    @Override
    public String getRefactoredReleaseName() {
        return "";
    }

    @Override
    public List<MethodInstance> getInitializedRefactoredMethods() {
        return List.of();
    }

    @Override
    public Map<String, MethodInstance> getFilledRefactoredMethods() {
        return Map.of();
    }


    @Override
    public boolean isRefactoredMethodsFilled() {
        return false;
    }

    @Override
    public void setMethods(Map<String, MethodInstance> methods) {

    }
}
