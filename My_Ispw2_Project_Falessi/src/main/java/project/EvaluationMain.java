package project;

import project.controllers.EvaluationFlow;

public class EvaluationMain {
    public static void main(String[] args) {

        //Inserire il nome del progetto che si vuole valutare
        //bookkeeper o openjpa
        EvaluationFlow evaluationFlow = new EvaluationFlow();
        evaluationFlow.executeFlow();
    }
}
