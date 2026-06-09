package project;

import project.controllers.EvaluationFlow;
import project.utils.Projects;

public class EvaluationMain {
    public static void main(String[] args) {
        //scegliere il progetto tra "openjpa" e "bookkeeper"
        String trainProject = "openjpa" ;
        String testProject  = "openjpa";
        EvaluationFlow evaluationFlow = new EvaluationFlow(trainProject, testProject);
        evaluationFlow.executeFlow();
    }
}
