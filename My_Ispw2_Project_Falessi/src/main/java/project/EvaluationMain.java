package project;

import project.controllers.EvaluationFlow;


public class EvaluationMain {
    public static void main(String[] args) {
        //choose the project between "openjpa" and "bookkeeper"
        String trainProject = "openjpa" ;
        String testProject  = "openjpa";
        EvaluationFlow evaluationFlow = new EvaluationFlow(trainProject, testProject);
        evaluationFlow.executeFlow();
    }
}
